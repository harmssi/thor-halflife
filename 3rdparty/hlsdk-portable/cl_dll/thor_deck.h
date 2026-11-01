/*
 * thor_deck.h - Thor Half-Life: HUD snapshot for the second-screen "HEV deck"
 *
 * Part of the modified HL SDK client library (see LICENSE in hlsdk-portable). Free, non-commercial.
 *
 * The client library keeps the HUD state (health, battery, weapons, ammo, pickups ...). On a
 * dual-screen device the Android app shows that state on the second display. This module
 *   - builds a compact JSON snapshot of the HUD state (ThorHUD_Snapshot, ~20 Hz),
 *   - accepts a small set of console commands from the deck (ThorHUD_Command), which are run on
 *     the game thread through the normal client command path (same as key binds),
 *   - hides the classic HUD elements on the main screen while the deck is active (cvar thor_deck).
 */
#pragma once
#if !defined( THOR_DECK_H )
#define THOR_DECK_H

struct playermove_s;

// true while the second-screen deck owns health/armor/ammo/weapon menu (cvar thor_deck != 0)
bool ThorDeck_HideHud( void );

void ThorDeck_Init( void );                        // from CHud::Init
void ThorDeck_Frame( double time );                // from HUD_Frame (every client frame)
void ThorDeck_PlayerMove( struct playermove_s *pm ); // from HUD_PlayerMove
void ThorDeck_Pickup( int type, int id, int count, const char *name ); // from HistoryResource::AddToHistory

extern "C"
{
// exported for the JNI bridge (thor/native/thorbridge.c)
int ThorHUD_Snapshot( char *buf, int size );
void ThorHUD_Command( const char *cmd );
}

#endif // THOR_DECK_H
