package haven;

public class ResizingWindow extends WindowX {
    private static final Coord gzsz = Window.bl.sz().add(5,5);

    private UI.Grab rsm;
    public Coord minsz = new Coord(0, 0);

    public ResizingWindow(Coord sz, String cap) {
	super(sz, cap);
    }

    public ResizingWindow(Coord sz, String cap, boolean lg) {
	super(sz, cap, lg);
    }

    public ResizingWindow(Coord sz, String cap, boolean lg, Coord tlo, Coord rbo) {
	super(sz, cap, lg, tlo, rbo);
    }

    @Override
    protected void initCfg() {
	if(cfg != null && cfg.sz != null){
	    asz = cfg.sz;
	    resize(asz);
	}
	super.initCfg();
    }

    @Override
    protected void setCfg() {
	super.setCfg();
	cfg.sz = asz;
    }

    @Override
    public boolean mousedown(Coord c, int button) {

	if(button == 1 && c.isect(sz.sub(gzsz), gzsz)) {
	    doff = c;
	    rsm = ui.grabmouse(this);
	    return true;
	}

	return super.mousedown(c, button);
    }

    @Override
    public boolean mouseup(Coord c, int button) {
	if(rsm != null) {
	    rsm.remove();
	    rsm = null;
	    updateCfg();
	} else {
	    super.mouseup(c, button);
	}
	return (true);
    }

    @Override
    public void mousemove(Coord c) {
	if(rsm != null) {
	    Coord d = c.sub(doff);
	    asz = asz.add(d);
	    asz.x = Math.max(minsz.x, asz.x);
	    asz.y = Math.max(minsz.y, asz.y);
	    resize(asz);
	    doff = c;
	} else {
	    super.mousemove(c);
	}
    }
}
