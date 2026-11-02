package de.harmssi.thorhalflife;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Display names for weapons, items and chapters (EN/DE/ES/FR). Plain text, no game assets. */
final class Names {
    private Names() {}

    private static final Map<String, String[]> WEAPONS = new HashMap<>();
    private static final Map<String, String[]> SECONDARY = new HashMap<>();
    private static final Map<String, String[]> ITEMS = new HashMap<>();

    private static void w(String id, String en, String de, String es, String fr) {
        WEAPONS.put(id, new String[]{en, de, es, fr});
    }

    static {
        w("weapon_crowbar", "Crowbar", "Brechstange", "Palanca", "Pied-de-biche");
        w("weapon_9mmhandgun", "9mm Pistol", "9-mm-Pistole", "Pistola 9 mm", "Pistolet 9 mm");
        w("weapon_glock", "9mm Pistol", "9-mm-Pistole", "Pistola 9 mm", "Pistolet 9 mm");
        w("weapon_357", ".357 Magnum", ".357 Magnum", "Magnum .357", "Magnum .357");
        w("weapon_python", ".357 Magnum", ".357 Magnum", "Magnum .357", "Magnum .357");
        w("weapon_9mmAR", "MP5", "MP5", "MP5", "MP5");
        w("weapon_mp5", "MP5", "MP5", "MP5", "MP5");
        w("weapon_shotgun", "Shotgun", "Schrotflinte", "Escopeta", "Fusil a pompe");
        w("weapon_crossbow", "Crossbow", "Armbrust", "Ballesta", "Arbalete");
        w("weapon_rpg", "RPG", "Raketenwerfer", "Lanzacohetes", "Lance-roquettes");
        w("weapon_gauss", "Tau Cannon", "Tau-Kanone", "Canon Tau", "Canon Tau");
        w("weapon_egon", "Gluon Gun", "Gluonenkanone", "Arma de gluones", "Fusil a gluons");
        w("weapon_hornetgun", "Hivehand", "Hivehand", "Hivehand", "Hivehand");
        w("weapon_handgrenade", "Grenades", "Granaten", "Granadas", "Grenades");
        w("weapon_satchel", "Satchel", "Rucksackladung", "Carga de mochila", "Charge a distance");
        w("weapon_tripmine", "Tripmine", "Laserfalle", "Mina laser", "Mine laser");
        w("weapon_snark", "Snarks", "Snarks", "Snarks", "Snarks");
        // Opposing Force
        w("weapon_pipewrench", "Pipe Wrench", "Rohrzange", "Llave inglesa", "Cle a molette");
        w("weapon_knife", "Combat Knife", "Kampfmesser", "Cuchillo", "Couteau");
        w("weapon_grapple", "Barnacle", "Barnacle", "Barnacle", "Barnacle");
        w("weapon_eagle", "Desert Eagle", "Desert Eagle", "Desert Eagle", "Desert Eagle");
        w("weapon_m249", "M249 SAW", "M249 SAW", "M249 SAW", "M249 SAW");
        w("weapon_sniperrifle", "Sniper Rifle", "Scharfschuetzengewehr", "Rifle de francotirador", "Fusil de precision");
        w("weapon_displacer", "Displacer", "Displacer", "Desplazador", "Displaceur");
        w("weapon_shockrifle", "Shock Roach", "Shock Roach", "Shock Roach", "Shock Roach");
        w("weapon_sporelauncher", "Spore Launcher", "Sporenwerfer", "Lanzaesporas", "Lance-spores");
        w("weapon_penguin", "Penguins", "Pinguine", "Pinguinos", "Pingouins");
        SECONDARY.put("weapon_9mmAR", new String[]{"AR grenades", "Granaten", "Granadas", "Grenades"});
        SECONDARY.put("weapon_mp5", new String[]{"AR grenades", "Granaten", "Granadas", "Grenades"});
        ITEMS.put("item_healthkit", new String[]{"Medkit", "Medikit", "Botiquin", "Trousse de soins"});
        ITEMS.put("item_battery", new String[]{"HEV battery", "HEV-Batterie", "Bateria HEV", "Batterie HEV"});
        ITEMS.put("item_longjump", new String[]{"Long jump module", "Weitsprungmodul", "Modulo de salto", "Module de saut"});
        ITEMS.put("item_suit", new String[]{"HEV suit", "HEV-Anzug", "Traje HEV", "Combinaison HEV"});
        ITEMS.put("item_antidote", new String[]{"Antidote", "Gegengift", "Antidoto", "Antidote"});
        ITEMS.put("item_security", new String[]{"Security card", "Sicherheitskarte", "Tarjeta", "Carte d'acces"});
        ITEMS.put("item_nightvision", new String[]{"Night vision", "Nachtsicht", "Vision nocturna", "Vision nocturne"});
        ITEMS.put("dmg_rad", new String[]{"Radiation", "Strahlung", "Radiacion", "Radiation"});
    }

    static int lang() {
        String l = Locale.getDefault().getLanguage();
        switch (l) {
            case "de": return 1;
            case "es": return 2;
            case "fr": return 3;
            default: return 0;
        }
    }

    static String weapon(String id) {
        String[] n = WEAPONS.get(id);
        if (n != null) {
            return n[lang()];
        }
        return pretty(id, "weapon_");
    }

    /** Name for a pickup feed entry: kind 'a' (ammo, "weapon_x" or "weapon_x#2"), 'w', 'i'. */
    static String pickup(char kind, String name) {
        if (kind == 'a') {
            boolean second = name.endsWith("#2");
            String wid = second ? name.substring(0, name.length() - 2) : name;
            if (second && SECONDARY.containsKey(wid)) {
                return SECONDARY.get(wid)[lang()];
            }
            return weapon(wid);
        }
        if (kind == 'w') {
            return weapon(name);
        }
        String[] n = ITEMS.get(name);
        return n != null ? n[lang()] : pretty(name, "item_");
    }

    static String secondary(String id) {
        String[] n = SECONDARY.get(id);
        return n != null ? n[lang()] : null;
    }

    private static String pretty(String id, String prefix) {
        String s = id.startsWith(prefix) ? id.substring(prefix.length()) : id;
        s = s.replace('_', ' ');
        return s.isEmpty() ? id : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ chapters

    private static final String[][] CHAPTERS = {
            // map prefix list, chapter title (the official English chapter names)
            {"t0a0", "Hazard Course"},
            {"c0a0", "Black Mesa Inbound"},
            {"c1a0", "Anomalous Materials"},
            {"c1a1", "Unforeseen Consequences"},
            {"c1a2", "Office Complex"},
            {"c1a3", "\"We've Got Hostiles\""},
            {"c1a4", "Blast Pit"},
            {"c2a1", "Power Up"},
            {"c2a2", "On A Rail"},
            {"c2a3", "Apprehension"},
            {"c2a4d c2a4e c2a4f c2a4g", "Questionable Ethics"},
            {"c2a4", "Residue Processing"},
            {"c2a5", "Surface Tension"},
            {"c3a1", "\"Forget About Freeman!\""},
            {"c3a2", "Lambda Core"},
            {"c4a1a c4a1b c4a1c c4a1d c4a1e c4a1f", "Interloper"},
            {"c4a1", "Xen"},
            {"c4a2", "Gonarch's Lair"},
            {"c4a3", "Nihilanth"},
            {"c5a1", "Endgame"},
            // Opposing Force (chapter title shown in the first map of each chapter)
            {"ofboot", "Boot Camp"},
            {"of0a", "Incoming"},
            {"of1a1 of1a2 of1a3 of1a4", "Welcome to Black Mesa"},
            {"of1a5 of1a6", "\"We Are Pulling Out\""},
            {"of2a1 of2a2 of2a3", "Missing in Action"},
            {"of2a4 of2a5 of2a6", "Friendly Fire"},
            {"of3a1 of3a2 of3a3", "We Are Not Alone"},
            {"of3a4 of3a5 of3a6", "Crush Depth"},
            {"of4a1 of4a2 of4a3", "Vicarious Reality"},
            {"of4a4 of4a5", "Pit Worm's Nest"},
            {"of5a", "Foxtrot Uniform"},
            {"of6a4b of6a5", "Worlds Collide"},
            {"of6a", "\"The Package\""},
            {"of7a", "Conclusion"},
            // Blue Shift
            {"ba_hazard", "Hazard Course"},
            {"ba_tram", "Living Quarters Outbound"},
            {"ba_security ba_maint ba_elevator", "Insecurity"},
            {"ba_canal", "Duty Calls"},
            // order: yard -> teleport1 -> xen1..6 -> teleport2 -> power1/2 -> teleport2 -> outro
            {"ba_yard ba_teleport1", "Captive Freight"},
            {"ba_xen", "Focal Point"},
            {"ba_power", "Power Struggle"},
            {"ba_outro", "A Leap of Faith"},
    };

    /** Chapter title for a map name (e.g. "c1a0d" -> "Anomalous Materials"), or null. */
    static String chapter(String map) {
        return chapter(map, null);
    }

    /**
     * Same, with the previous map: Blue Shift visits ba_teleport2 twice (from Xen = "Focal Point",
     * back from the power plant = "A Leap of Faith").
     */
    static String chapter(String map, String previousMap) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        String m = map.toLowerCase(Locale.ROOT);
        if (m.equals("ba_teleport2")) {
            boolean back = previousMap != null && previousMap.toLowerCase(Locale.ROOT).startsWith("ba_power");
            return back ? "A Leap of Faith" : "Focal Point";
        }
        for (String[] c : CHAPTERS) {
            for (String prefix : c[0].split(" ")) {
                // Half-Life: 5-letter entries are exact map names (c2a4 vs c2a4d); other games: prefixes
                boolean exact = prefix.length() == 5 && prefix.charAt(0) == 'c';
                if (exact ? m.equals(prefix) : m.startsWith(prefix)) {
                    return c[1];
                }
            }
        }
        return null;
    }
}
