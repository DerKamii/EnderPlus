package haven;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class Radar {
    public static final String CONFIG_JSON = "radar.json";
    private static final Map<String, String> gob2icon = new HashMap<>();
    
    public static boolean process(GobIcon icon) {
	try {
	    String gres = icon.gob.resid();
	    String ires = icon.res.get().name;
	    if(gres != null && ires != null) {
		if(!ires.equals(gob2icon.get(gres))) {
		    gob2icon.put(gres, ires);
		    //if(gres.contains("kritter")) Debug.log.printf("gob2icon.put(\"%s\", \"%s\");%n", gres, ires);
		}
		return true;
	    }
	} catch (Loading ignored) {}
	return false;
    }
    
    public static GobIcon getIcon(Gob gob) {
	String resname = gob2icon.get(gob.resid());
	if(resname != null) {
	    return new GobIcon(gob, Resource.remote().load(resname));
	}
	return null;
    }
    
    public static void addCustomSettings(Map<String, GobIcon.Setting> settings, UI ui) {
	List<RadarItemVO> items = load(Config.loadJarFile(CONFIG_JSON));
	items.addAll(load(Config.loadFSFile(CONFIG_JSON)));
	for (RadarItemVO item : items) {
	    gob2icon.put(item.match, item.icon);
	    addSetting(settings, item.icon, item.visible);
	}
	addMissingStones(settings);
	ui.sess.glob.oc.gobAction(Gob::iconUpdated);
    }
    
    private static void addMissingStones(Map<String, GobIcon.Setting> settings)
    {
	putStone("cinnabar");
	addSetting(settings, "gfx/invobjs/cinnabar", false);
	putStone("chert");
	addSetting(settings, "gfx/invobjs/chert", false);
	putStone("graywacke");
	addSetting(settings, "gfx/invobjs/graywacke", false);
	putStone("serpentine");
	addSetting(settings, "gfx/invobjs/serpentine", false);
    }
    
    private static void putStone(String name)
    {
	gob2icon.put("gfx/terobjs/bummlings/" + name + "1", "gfx/invobjs/" + name);
	gob2icon.put("gfx/terobjs/bummlings/" + name + "2", "gfx/invobjs/" + name);
	gob2icon.put("gfx/terobjs/bummlings/" + name + "3", "gfx/invobjs/" + name);
    }
    
    private static void addSetting(Map<String, GobIcon.Setting> settings, String res, boolean def) {
	if(!settings.containsKey(res)) {
	    GobIcon.Setting cfg = new GobIcon.Setting(new Resource.Spec(null, res));
	    cfg.show = cfg.defshow = def;
	    settings.put(res, cfg);
	}
    }
    
    private static List<RadarItemVO> load(String json) {
	if(json != null) {
	    Gson gson = (new GsonBuilder()).setPrettyPrinting().create();
	    try {
		return gson.fromJson(json, new TypeToken<List<RadarItemVO>>() {
		}.getType());
	    } catch (Exception ignored) {}
	}
	return new LinkedList<>();
    }
    
    private static class RadarItemVO {
	String match, icon;
	boolean visible;
    }
}
