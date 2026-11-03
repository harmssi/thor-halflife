/*
 * thor_deck.cpp - Thor Half-Life: HUD snapshot + deck commands (see thor_deck.h)
 *
 * Part of the modified HL SDK client library (see LICENSE in hlsdk-portable). Free, non-commercial.
 *
 * Rules:
 *   - read-only access to the HUD state, no game state is ever changed from here;
 *   - every deck action is a console command that runs on the game thread through the normal
 *     client command path (ClientCmd), exactly like a key bind;
 *   - weapon selection uses the same path as the HUD weapon menu (ServerCmd( "weapon_xxx" )).
 */
#include "hud.h"
#include "cl_util.h"
#include "ammohistory.h"
#include "pm_defs.h"
#include "thor_deck.h"

#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define THOR_EXPORT __attribute__(( visibility( "default" )))

extern WEAPON *gpActiveSel;
extern int g_weaponselect;

static cvar_t *thor_deck;

// ------------------------------------------------------------------ shared state (mutex)

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_snapshot[24576];
static int g_snapshotLen;

#define CMD_QUEUE 16
#define CMD_LEN 64
static char g_cmds[CMD_QUEUE][CMD_LEN];
static int g_cmdHead, g_cmdTail;

// ------------------------------------------------------------------ game-thread state

struct ThorFeed
{
	int seq;
	char kind;      // 'a' ammo, 'w' weapon, 'i' item
	char name[48];
	int count;
	float time;
};

#define FEED_MAX 8
static ThorFeed g_feed[FEED_MAX];
static int g_feedSeq;

static int g_waterlevel, g_onground = 1, g_movetype;
static float g_fallVelocity, g_speed;
static float g_underwaterSince = -1.0f;
static float g_lastSnapshot = -1.0f;
static double g_lastFrameTime;
static int g_seq;
static float g_unUseAt = -1.0f;

struct ThorDeckAccess
{
	static int Battery() { return gHUD.m_Battery.m_iBat; }
#if defined( THOR_DECK_NIGHTVISION )
	// Opposing Force: the "Flashlight" message drives the night vision (CHudNightvision)
	static int FlashOn() { return gHUD.m_Nightvision.m_fOn; }
#else
	static int FlashOn() { return gHUD.m_Flash.m_fOn; }
#endif
	static int FlashBat() { return gHUD.m_Flash.m_iBat; }
	static int Geiger() { return gHUD.m_Geiger.m_iGeigerRange; }
	static int Train() { return gHUD.m_Train.m_iPos; }
	static int DamageBits() { return gHUD.m_Health.m_bitsDamage; }
	static float Attack( int i )
	{
		switch( i )
		{
		case 0: return gHUD.m_Health.m_fAttackFront;
		case 1: return gHUD.m_Health.m_fAttackRight;
		case 2: return gHUD.m_Health.m_fAttackRear;
		default: return gHUD.m_Health.m_fAttackLeft;
		}
	}
	static WEAPON *Current() { return gHUD.m_Ammo.m_pWeapon; }
};

bool ThorDeck_HideHud( void )
{
	return thor_deck && thor_deck->value != 0.0f;
}

// ------------------------------------------------------------------ deck commands

static bool HasWeapon( WEAPON *p )
{
	return p && p->iId > 0 && p->iId < 32 && ( gHUD.m_iWeaponBits & ( 1 << p->iId ));
}

// m_fPlayerDead is only set by CurWeapon(-1,-1), which CHudAmmo::MsgFunc_CurWeapon returns before
// reaching (iId < 1); on death the server also strips the suit bit, so health is the reliable signal
static bool Dead( void )
{
	return gHUD.m_fPlayerDead || gHUD.m_Health.m_iHealth <= 0;
}

static bool CanSelect( void )
{
	if( Dead() || ( gHUD.m_iHideHUDDisplay & ( HIDEHUD_WEAPONS | HIDEHUD_ALL )))
		return false;
	if( !( gHUD.m_iWeaponBits & ( 1 << WEAPON_SUIT )))
		return false;
	return true;
}

// same as the weapon menu confirming a selection (CHudAmmo::Think)
static void SelectWeapon( WEAPON *p )
{
	ServerCmd( p->szName );
	g_weaponselect = p->iId;
	gpActiveSel = NULL;
	PlaySound( "common/wpn_select.wav", 1 );
}

static void Cmd_ThorSelect( void )
{
	if( gEngfuncs.Cmd_Argc() < 2 || !CanSelect())
		return;
	int id = atoi( gEngfuncs.Cmd_Argv( 1 ));
	if( id <= 0 || id >= MAX_WEAPONS )
		return;
	WEAPON *p = gWR.GetWeapon( id );
	if( !HasWeapon( p ) || !gWR.HasAmmo( p ))
		return;
	SelectWeapon( p );
}

// instant next/previous weapon without the (hidden) weapon menu
static void CycleWeapon( int dir )
{
	if( !CanSelect())
		return;
	WEAPON *order[MAX_WEAPON_SLOTS * MAX_WEAPON_POSITIONS];
	int n = 0, cur = -1;
	WEAPON *active = ThorDeckAccess::Current();
	for( int s = 0; s < MAX_WEAPON_SLOTS; s++ )
	{
		for( int pos = 0; pos < MAX_WEAPON_POSITIONS; pos++ )
		{
			WEAPON *p = gWR.GetWeaponSlot( s, pos );
			if( !HasWeapon( p ))
				continue;
			if( p == active )
				cur = n;
			order[n++] = p;
		}
	}
	if( n == 0 )
		return;
	for( int step = 1; step <= n; step++ )
	{
		int i = cur < 0 ? ( dir > 0 ? step - 1 : n - step ) : (( cur + dir * step ) % n + n ) % n;
		WEAPON *p = order[i];
		if( p == active )
			return;
		if( gWR.HasAmmo( p ))
		{
			SelectWeapon( p );
			return;
		}
	}
}

static void Cmd_ThorNext( void ) { CycleWeapon( 1 ); }
static void Cmd_ThorPrev( void ) { CycleWeapon( -1 ); }

// tap "use" from the deck: +use now, -use a moment later (a single tap on the use key)
static void Cmd_ThorUse( void )
{
	ClientCmd( "+use\n" );
	g_unUseAt = gEngfuncs.GetClientTime() + 0.15f;
}

void ThorDeck_Init( void )
{
	const char *env = getenv( "THOR_DECK" );
	thor_deck = gEngfuncs.pfnRegisterVariable( "thor_deck", ( env && env[0] == '1' ) ? "1" : "0", 0 );
	gEngfuncs.pfnAddCommand( "thor_select", Cmd_ThorSelect );
	gEngfuncs.pfnAddCommand( "thor_wnext", Cmd_ThorNext );
	gEngfuncs.pfnAddCommand( "thor_wprev", Cmd_ThorPrev );
	gEngfuncs.pfnAddCommand( "thor_use", Cmd_ThorUse );
}

// only these commands are accepted from the deck
static bool Allowed( const char *c )
{
	static const char *exact[] = {
		"thor_wnext", "thor_wprev", "thor_use", "impulse 100", "savequick", "loadquick",
		"cancelselect", "togglemenu", "lastinv", "+use", "-use", "thor_deck 0", "thor_deck 1", NULL
	};
	for( int i = 0; exact[i]; i++ )
	{
		if( !strcmp( c, exact[i] ))
			return true;
	}
	if( !strncmp( c, "thor_select ", 12 ))
	{
		for( const char *p = c + 12; *p; p++ )
		{
			if( *p < '0' || *p > '9' )
				return false;
		}
		return c[12] != 0;
	}
	return false;
}

extern "C" THOR_EXPORT void ThorHUD_Command( const char *cmd )
{
	if( !cmd || !Allowed( cmd ))
		return;
	pthread_mutex_lock( &g_lock );
	int next = ( g_cmdHead + 1 ) % CMD_QUEUE;
	if( next != g_cmdTail )
	{
		snprintf( g_cmds[g_cmdHead], CMD_LEN, "%s", cmd );
		g_cmdHead = next;
	}
	pthread_mutex_unlock( &g_lock );
}

extern "C" THOR_EXPORT int ThorHUD_Snapshot( char *buf, int size )
{
	pthread_mutex_lock( &g_lock );
	int n = g_snapshotLen;
	if( n >= size )
		n = 0;
	if( n > 0 )
		memcpy( buf, g_snapshot, n + 1 );
	pthread_mutex_unlock( &g_lock );
	return n;
}

// ------------------------------------------------------------------ pickups / movement

static void JsonSafe( char *dst, size_t size, const char *src )
{
	size_t j = 0;
	for( size_t i = 0; src && src[i] && j + 1 < size; i++ )
	{
		char c = src[i];
		if( c == '"' || c == '\\' || (unsigned char)c < 32 )
			continue;
		dst[j++] = c;
	}
	dst[j] = 0;
}

void ThorDeck_Pickup( int type, int id, int count, const char *name )
{
	ThorFeed f;
	memset( &f, 0, sizeof( f ));
	f.count = count;
	f.time = gEngfuncs.GetClientTime();

	if( type == HISTSLOT_AMMO )
	{
		// ammo has no name of its own: report the weapon that uses it (+ primary/secondary)
		f.kind = 'a';
		for( int i = 1; i < MAX_WEAPONS; i++ )
		{
			WEAPON *w = gWR.GetWeapon( i );
			if( !w->iId )
				continue;
			if( w->iAmmoType == id )
			{
				JsonSafe( f.name, sizeof( f.name ), w->szName );
				break;
			}
			if( w->iAmmo2Type == id )
			{
				char tmp[48];
				snprintf( tmp, sizeof( tmp ), "%s#2", w->szName );
				JsonSafe( f.name, sizeof( f.name ), tmp );
				break;
			}
		}
		if( !f.name[0] )
			snprintf( f.name, sizeof( f.name ), "ammo%d", id );
	}
	else if( type == HISTSLOT_WEAP )
	{
		f.kind = 'w';
		WEAPON *w = ( id > 0 && id < MAX_WEAPONS ) ? gWR.GetWeapon( id ) : NULL;
		JsonSafe( f.name, sizeof( f.name ), w ? w->szName : "weapon" );
	}
	else if( type == HISTSLOT_ITEM )
	{
		f.kind = 'i';
		JsonSafe( f.name, sizeof( f.name ), name ? name : "item" );
	}
	else
		return;

	f.seq = ++g_feedSeq;
	memmove( &g_feed[1], &g_feed[0], sizeof( ThorFeed ) * ( FEED_MAX - 1 ));
	g_feed[0] = f;
}

void ThorDeck_PlayerMove( struct playermove_s *pm )
{
	if( !pm )
		return;
	g_waterlevel = pm->waterlevel;
	g_onground = pm->onground != -1;
	g_movetype = pm->movetype;
	g_fallVelocity = pm->flFallVelocity;
	g_speed = sqrtf( pm->velocity[0] * pm->velocity[0] + pm->velocity[1] * pm->velocity[1] );
}

// ------------------------------------------------------------------ snapshot

struct Out
{
	char *p;
	int left;
	void add( const char *fmt, ... )
	{
		if( left <= 1 )
			return;
		va_list va;
		va_start( va, fmt );
		int n = vsnprintf( p, left, fmt, va );
		va_end( va );
		if( n < 0 )
			return;
		if( n >= left )
			n = left - 1;
		p += n;
		left -= n;
	}
};

static void BuildSnapshot( float now )
{
	static char tmp[sizeof( g_snapshot )];
	Out o = { tmp, (int)sizeof( tmp ) };

	char map[64] = "";
	const char *level = gEngfuncs.pfnGetLevelName ? gEngfuncs.pfnGetLevelName() : "";
	if( level && level[0] )
	{
		const char *base = strrchr( level, '/' );
		base = base ? base + 1 : level;
		JsonSafe( map, sizeof( map ), base );
		char *dot = strrchr( map, '.' );
		if( dot )
			*dot = 0;
	}

	float ang[3] = { 0, 0, 0 };
	gEngfuncs.GetViewAngles( ang );
	cl_entity_t *pl = gEngfuncs.GetLocalPlayer();
	float org[3] = { 0, 0, 0 };
	if( pl )
	{
		org[0] = pl->origin[0];
		org[1] = pl->origin[1];
		org[2] = pl->origin[2];
	}

	// air estimate: the server gives 12 s of air (AIRTIME) once the head is under water
	if( g_waterlevel >= 3 )
	{
		if( g_underwaterSince < 0 || g_underwaterSince > now )
			g_underwaterSince = now;
	}
	else
		g_underwaterSince = -1.0f;
	float air = g_underwaterSince < 0 ? -1.0f : 12.0f - ( now - g_underwaterSince );

	WEAPON *cur = ThorDeckAccess::Current();
	WEAPON *pending = ( gpActiveSel && gpActiveSel != (WEAPON *)1 ) ? gpActiveSel : NULL;
	int suit = ( gHUD.m_iWeaponBits & ( 1 << WEAPON_SUIT )) ? 1 : 0;

	o.add( "{\"v\":1,\"seq\":%d,\"t\":%.2f,\"map\":\"%s\",\"deck\":%d,", ++g_seq, now, map, ThorDeck_HideHud() ? 1 : 0 );
	o.add( "\"hp\":%d,\"ap\":%d,\"suit\":%d,\"dead\":%d,\"hide\":%d,", gHUD.m_Health.m_iHealth, ThorDeckAccess::Battery(),
		suit, Dead() ? 1 : 0, gHUD.m_iHideHUDDisplay );
	o.add( "\"fl\":%d,\"flbat\":%d,\"dmg\":%d,\"geiger\":%d,\"train\":%d,", ThorDeckAccess::FlashOn(), ThorDeckAccess::FlashBat(),
		ThorDeckAccess::DamageBits(), ThorDeckAccess::Geiger(), ThorDeckAccess::Train());
	o.add( "\"pain\":[%.2f,%.2f,%.2f,%.2f],", ThorDeckAccess::Attack( 0 ), ThorDeckAccess::Attack( 1 ), ThorDeckAccess::Attack( 2 ), ThorDeckAccess::Attack( 3 ));
	o.add( "\"water\":%d,\"air\":%.1f,\"ground\":%d,\"fallv\":%.0f,\"speed\":%.0f,\"move\":%d,", g_waterlevel, air, g_onground,
		g_fallVelocity, g_speed, g_movetype );
	o.add( "\"yaw\":%.1f,\"pitch\":%.1f,\"pos\":[%.0f,%.0f,%.0f],", ang[1], ang[0], org[0], org[1], org[2] );
	o.add( "\"slots\":%d,\"cur\":%d,\"sel\":%d,\"w\":[", MAX_WEAPON_SLOTS, cur ? cur->iId : 0, pending ? pending->iId : 0 );

	bool first = true;
	for( int s = 0; s < MAX_WEAPON_SLOTS; s++ )
	{
		for( int pos = 0; pos < MAX_WEAPON_POSITIONS; pos++ )
		{
			WEAPON *p = gWR.GetWeaponSlot( s, pos );
			if( !HasWeapon( p ))
				continue;
			char name[48];
			JsonSafe( name, sizeof( name ), p->szName );
			int a1 = p->iAmmoType >= 0 ? gWR.CountAmmo( p->iAmmoType ) : -1;
			int a2 = p->iAmmo2Type >= 0 ? gWR.CountAmmo( p->iAmmo2Type ) : -1;
			// [id,"name",slot,pos,clip,ammo1,max1,ammo2,max2,selectable]
			o.add( "%s[%d,\"%s\",%d,%d,%d,%d,%d,%d,%d,%d]", first ? "" : ",", p->iId, name, p->iSlot, p->iSlotPos,
				p->iClip, a1, p->iMax1, a2, p->iMax2, gWR.HasAmmo( p ) ? 1 : 0 );
			first = false;
		}
	}
	o.add( "],\"feed\":[" );
	first = true;
	for( int i = 0; i < FEED_MAX; i++ )
	{
		ThorFeed *f = &g_feed[i];
		if( !f->seq )
			continue;
		float age = now - f->time;
		if( age < 0 ) // new map / time reset
			age = 0;
		o.add( "%s[%d,\"%c\",\"%s\",%d,%.1f]", first ? "" : ",", f->seq, f->kind, f->name, f->count, age );
		first = false;
	}
	o.add( "]}" );

	int len = (int)( o.p - tmp );
	pthread_mutex_lock( &g_lock );
	memcpy( g_snapshot, tmp, len + 1 );
	g_snapshotLen = len;
	pthread_mutex_unlock( &g_lock );
}

void ThorDeck_Frame( double time )
{
	(void)time;
	// run queued deck commands on the game thread (normal console path, like key binds)
	char cmds[CMD_QUEUE][CMD_LEN];
	int n = 0;
	pthread_mutex_lock( &g_lock );
	while( g_cmdTail != g_cmdHead && n < CMD_QUEUE )
	{
		memcpy( cmds[n++], g_cmds[g_cmdTail], CMD_LEN );
		g_cmdTail = ( g_cmdTail + 1 ) % CMD_QUEUE;
	}
	pthread_mutex_unlock( &g_lock );
	for( int i = 0; i < n; i++ )
	{
		char line[CMD_LEN + 2];
		snprintf( line, sizeof( line ), "%s\n", cmds[i] );
		ClientCmd( line );
	}

	float now = gEngfuncs.GetClientTime();
	if( g_unUseAt > 0 && ( now >= g_unUseAt || now < g_unUseAt - 1.0f ))
	{
		ClientCmd( "-use\n" );
		g_unUseAt = -1.0f;
	}

	// refresh the snapshot ~20 times per second (and right after a time jump, e.g. map change)
	double real = gEngfuncs.pfnSys_FloatTime ? gEngfuncs.pfnSys_FloatTime() : now;
	if( real - g_lastFrameTime >= 0.05 || real < g_lastFrameTime || now < g_lastSnapshot )
	{
		g_lastFrameTime = real;
		g_lastSnapshot = now;
		BuildSnapshot( now );
	}
}
