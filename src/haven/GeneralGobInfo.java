package haven;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class GeneralGobInfo extends GobInfo {
    private static final int TREE_START = 10;
    private static final int BUSH_START = 30;
    private static final double TREE_MULT = 100.0 / (100.0 - TREE_START);
    private static final double BUSH_MULT = 100.0 / (100.0 - BUSH_START);
    private static final Color Q_COL = new Color(235, 252, 255, 255);
    private static final Color BG = new Color(0, 0, 0, 84);
    public static Pattern GOB_Q = Pattern.compile("Quality: (\\d+)");
    private static final Map<Long, Integer> gobQ = new LinkedHashMap<Long, Integer>() {
	@Override
	protected boolean removeEldestEntry(Map.Entry eldest) {
	    return size() > 50;
	}
    };
    private GobHealth health;
    int q;

    protected GeneralGobInfo(Gob owner) {
	super(owner);
	q = gobQ.getOrDefault(gob.id, 0);
    }
    
    
    public void setQ(int q) {
	gobQ.put(gob.id, q);
	this.q = q;
    }
    
    @Override
    protected boolean enabled() {
	return CFG.DISPLAY_GOB_INFO.get();
    }

    @Override
    protected Tex render() {
	if(gob == null || gob.getres() == null) { return null;}

	BufferedImage growth = growth();
	BufferedImage health = health();
	BufferedImage quality = quality();

	if(growth == null && health == null && quality == null) {
	    return null;
	}

	return new TexI(ItemInfo.catimgsh(3, 0, BG, health, growth, quality));
    }
    
    @Override
    public void dispose() {
	health = null;
	super.dispose();
    }

    private BufferedImage quality() {
	if(q != 0) {
	    return Text.std.renderstroked(String.format("Q: %d", q), Q_COL, Color.BLACK).img;
	}
	return null;
    }
    
    private BufferedImage health() {
	health = gob.getattr(GobHealth.class);
	if(health != null) {
	    return health.text();
	}

	return null;
    }

    private BufferedImage growth() {
	Text.Line line = null;
 
	if(isSpriteKind(gob, "GrowingPlant", "TrellisPlant")) {
	    int maxStage = 0;
	    for (FastMesh.MeshRes layer : gob.getres().layers(FastMesh.MeshRes.class)) {
		if(layer.id / 10 > maxStage) {
		    maxStage = layer.id / 10;
		}
	    }
	    Message data = getDrawableData(gob);
	    if(data != null) {
		int stage = data.uint8();
		if(stage > maxStage) {stage = maxStage;}
		Color c = Utils.blendcol((double) stage / maxStage, Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN);
		line = Text.std.renderstroked(String.format("%d/%d", stage, maxStage), c, Color.BLACK);
	    }
	} else if(isSpriteKind(gob, "Tree")) {
	    Message data = getDrawableData(gob);
	    if(data != null && !data.eom()) {
		data.skip(1);
		int growth = data.eom() ? -1 : data.uint8();
		if(growth < 100 && growth >= 0) {
		    if(gob.is(GobTag.TREE)) {
			growth = (int) (TREE_MULT * (growth - TREE_START));
		    } else if(gob.is(GobTag.BUSH)) {
			growth = (int) (BUSH_MULT * (growth - BUSH_START));
		    }
		    Color c = Utils.blendcol(growth / 100.0, Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN);
		    line = Text.std.renderstroked(String.format("%d%%", growth), c, Color.BLACK);
		}
	    }
	}

	if(line != null) {
	    return line.img;
	}
	return null;
    }

    private static Message getDrawableData(Gob gob) {
	Drawable dr = gob.drawable;
	ResDrawable d = (dr instanceof ResDrawable) ? (ResDrawable) dr : null;
	if(d != null)
	    return d.sdt.clone();
	else
	    return null;
    }
    
    private static boolean isSpriteKind(Gob gob, String... kind) {
	List<String> kinds = Arrays.asList(kind);
	boolean result = false;
	Class spc;
	Drawable d = gob.drawable;
	Resource.CodeEntry ce = gob.getres().layer(Resource.CodeEntry.class);
	if(ce != null) {
	    spc = ce.get("spr");
	    result = spc != null && (kinds.contains(spc.getSimpleName()) || kinds.contains(spc.getSuperclass().getSimpleName()));
	}
	if(!result) {
	    if(d instanceof ResDrawable) {
		Sprite spr = ((ResDrawable) d).spr;
		if(spr == null) {throw new Loading();}
		spc = spr.getClass();
		result = kinds.contains(spc.getSimpleName()) || kinds.contains(spc.getSuperclass().getSimpleName());
	    }
	}
	return result;
    }

    @Override
    public String toString() {
	Resource res = gob.getres();
	return String.format("GobInfo<%s>", res != null ? res.name : "<loading>");
    }
}