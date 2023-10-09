/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import me.ender.Reflect;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

import static haven.Inventory.invsq;

public class Makewindow extends Widget {
    public static final Text qmodl = Text.render(L10N.label("Quality:"));
    public static final Text tooll = Text.render(L10N.label("Tools:"));
    public static final Coord boff = UI.scale(new Coord(7, 9));
    public String rcpnm;
    public List<Input> inputs = Collections.emptyList();
    public List<SpecWidget> outputs = Collections.emptyList();
    public List<Indir<Resource>> qmod = Collections.emptyList();
    public List<Indir<Resource>> tools = new ArrayList<>();;
    private int xoff = UI.scale(45), qmy = UI.scale(38), outy = UI.scale(65);
    public static final Text.Foundry nmf = new Text.Foundry(Text.serif, 20).aa(true);
    private static double softcap = 0;
    private static Tex softTex = null;

    @RName("make")
    public static class $_ implements Factory {
	public Widget create(UI ui, Object[] args) {
	    return(new Makewindow((String)args[0]));
	}
    }

    private static final OwnerContext.ClassResolver<Makewindow> ctxr = new OwnerContext.ClassResolver<Makewindow>()
	.add(Makewindow.class, wdg -> wdg)
	.add(Glob.class, wdg -> wdg.ui.sess.glob)
	.add(Session.class, wdg -> wdg.ui.sess);
    public class Spec implements GSprite.Owner, ItemInfo.SpriteOwner {
	public Indir<Resource> res;
	public MessageBuf sdt;
	public Tex num;
	private GSprite spr;
	private Object[] rawinfo;
	private List<ItemInfo> info;

	public Spec(Indir<Resource> res, Message sdt, int num, Object[] info) {
	    this.res = res;
	    this.sdt = new MessageBuf(sdt);
	    if(num >= 0)
		this.num = new TexI(Utils.outline2(Text.render(Integer.toString(num), Color.WHITE).img, Utils.contrast(Color.WHITE)));
	    else
		this.num = null;
	    this.rawinfo = info;
	}

	public GSprite sprite() {
	    if(spr == null)
		spr = GSprite.create(this, res.get(), sdt.clone());;
	    return(spr);
	}

	public void draw(GOut g) {
	    try {
		sprite().draw(g);
	    } catch(Loading e) {}
	    if(num != null)
		g.aimage(num, Inventory.sqsz, 1.0, 1.0);
	}

	private int opt = 0;
	public boolean opt() {
	    if(opt == 0)
		opt = (ItemInfo.find(Optional.class, info()) != null) ? 1 : 2;
	    return(opt == 1);
	}

	public BufferedImage shorttip() {
	    List<ItemInfo> info = info();
	    if(info.isEmpty()) {
		Resource.Tooltip tt = res.get().layer(Resource.tooltip);
		if(tt == null)
		    return(null);
		return(Text.render(tt.t).img);
	    }
	    return(ItemInfo.shorttip(info()));
	}
	public BufferedImage longtip() {
	    List<ItemInfo> info = info();
	    BufferedImage img;
	    if(info.isEmpty()) {
		Resource.Tooltip tt = res.get().layer(Resource.tooltip);
		if(tt == null)
		    return(null);
		img = Text.render(tt.t).img;
	    } else {
		img = ItemInfo.longtip(info);
	    }
	    Resource.Pagina pg = res.get().layer(Resource.pagina);
	    if(pg != null)
		img = ItemInfo.catimgs(0, img, RichText.render("\n" + pg.text, 200).img);
	    return(img);
	}

	private Random rnd = null;
	public Random mkrandoom() {
	    if(rnd == null)
		rnd = new Random();
	    return(rnd);
	}
	public Resource getres() {return(res.get());}
	public <T> T context(Class<T> cl) {return(ctxr.context(cl, Makewindow.this));}
	@Deprecated
	public Glob glob() {return(ui.sess.glob);}

	public List<ItemInfo> info() {
	    if(info == null)
		info = ItemInfo.buildinfo(this, rawinfo);
	    return(info);
	}
	public Resource resource() {return(res.get());}
    }

    public static final KeyBinding kb_make = KeyBinding.get("make/one", KeyMatch.forcode(java.awt.event.KeyEvent.VK_ENTER, 0));
    public static final KeyBinding kb_makeall = KeyBinding.get("make/all", KeyMatch.forcode(java.awt.event.KeyEvent.VK_ENTER, KeyMatch.C));
    public Makewindow(String rcpnm) {
	int inputW = add(new Label("Input:"), new Coord(0, UI.scale(8))).sz.x;
	int resultW = add(new Label("Result:"), new Coord(0, outy + UI.scale(8))).sz.x;
	xoff = Math.max(inputW, resultW) + UI.scale(10);
	add(new Button(UI.scale(85), "Craft"), UI.scale(new Coord(230, 75))).action(() -> wdgmsg("make", 0)).setgkey(kb_make);
	add(new Button(UI.scale(85), "Craft All"), UI.scale(new Coord(325, 75))).action(() -> wdgmsg("make", 1)).setgkey(kb_makeall);
	pack();
	this.rcpnm = rcpnm;
    }

    public void uimsg(String msg, Object... args) {
	if(msg == "inpop") {
	    List<Spec> inputs = new ArrayList<>();
	    for(int i = 0; i < args.length;) {
		int resid = (Integer)args[i++];
		Message sdt = (args[i] instanceof byte[]) ? new MessageBuf((byte[])args[i++]) : MessageBuf.nil;
		int num = (Integer)args[i++];
		Object[] info = {};
		if((i < args.length) && (args[i] instanceof Object[]))
		    info = (Object[])args[i++];
		inputs.add(new Spec(ui.sess.getres(resid), sdt, num, info));
	    }
	    ui.sess.glob.loader.defer(() -> {
		    List<Input> wdgs = new ArrayList<>();
		    int idx = 0;
		    for(Spec spec : inputs)
			wdgs.add(new Input(spec, idx++));
		    synchronized(ui) {
			for(Widget w : this.inputs)
			    w.destroy();
			Position pos = new Position(xoff, 0);
			SpecWidget prev = null;
			for(Input wdg : wdgs) {
			    if((prev != null) && (wdg.opt != false))
				pos = pos.adds(10, 0);
			    add(wdg, pos);
			    pos = pos.add(Inventory.sqsz.x, 0);
			    prev = wdg;
			}
			this.inputs = wdgs;
		    }
		}, null);
	} else if(msg == "opop") {
	    List<Spec> outputs = new ArrayList<Spec>();
	    for(int i = 0; i < args.length;) {
		int resid = (Integer)args[i++];
		Message sdt = (args[i] instanceof byte[]) ? new MessageBuf((byte[])args[i++]) : MessageBuf.nil;
		int num = (Integer)args[i++];
		Object[] info = {};
		if((i < args.length) && (args[i] instanceof Object[]))
		    info = (Object[])args[i++];
		outputs.add(new Spec(ui.sess.getres(resid), sdt, num, info));
	    }
	    ui.sess.glob.loader.defer(() -> {
		    List<SpecWidget> wdgs = new ArrayList<>();
		    for(Spec spec : outputs)
			wdgs.add(new SpecWidget(spec));
		    synchronized(ui) {
			for(Widget w : this.outputs)
			    w.destroy();
			Position pos = new Position(xoff, outy);
			SpecWidget prev = null;
			for(SpecWidget wdg : wdgs) {
			    if((prev != null) && (wdg.opt != prev.opt))
				pos = pos.adds(10, 0);
			    add(wdg, pos);
			    pos = pos.add(Inventory.sqsz.x, 0);
			    prev = wdg;
			}
			this.outputs = wdgs;
		    }
		}, null);
	} else if(msg == "qmod") {
	    List<Indir<Resource>> qmod = new ArrayList<Indir<Resource>>();
	    for(Object arg : args)
		qmod.add(ui.sess.getres((Integer)arg));
	    this.qmod = qmod;
	} else if(msg == "tool") {
	    tools.add(ui.sess.getres((Integer)args[0]));
	} else if(msg == "inprcps") {
	    int idx = (Integer)args[0];
	    List<MenuGrid.Pagina> rcps = new ArrayList<>();
	    GameUI gui = getparent(GameUI.class);
	    if((gui != null) && (gui.menu != null)) {
		for(int a = 1; a < args.length; a++)
		    rcps.add(gui.menu.paginafor(ui.sess.getres((Integer)args[a])));
	    }
	    inputs.get(idx).recipes(rcps);
	} else {
	    super.uimsg(msg, args);
	}
    }

    public static final Coord qmodsz = UI.scale(20, 20);
    private static final Map<Indir<Resource>, Tex> qmicons = new WeakHashMap<>();
    private Tex qmicon(Indir<Resource> qm) {
        synchronized (qmicons) {
	    return qmicons.computeIfAbsent(qm, Makewindow.this::buildQTex);
	}
    }

    public static class SpecWidget extends Widget {
	public final Spec spec;
	public final boolean opt;

	public SpecWidget(Spec spec) {
	    super(invsq.sz());
	    this.spec = spec;
	    opt = spec.opt();
	}

	public void draw(GOut g) {
	    if(opt) {
		g.chcolor(0, 255, 0, 255);
		g.image(invsq, Coord.z);
		g.chcolor();
	    } else {
		g.image(invsq, Coord.z);
	    }
	    spec.draw(g);
	}

	private double hoverstart;
	Indir<Object> stip, ltip;
	public Object tooltip(Coord c, Widget prev) {
	    double now = Utils.rtime();
	    if(prev == this) {
	    } else if(prev instanceof SpecWidget) {
		double ps = ((SpecWidget)prev).hoverstart;
		hoverstart = (now - ps < 1.0) ? now : ps;
	    } else {
		hoverstart = now;
	    }
	    if(now - hoverstart >= 1.0) {
		if(stip == null) {
		    BufferedImage tip = spec.shorttip();
		    Tex tt = (tip == null) ? null : new TexI(tip);
		    stip = () -> tt;
		}
		return(stip);
	    } else {
		if(ltip == null) {
		    BufferedImage tip = spec.longtip();
		    Tex tt = (tip == null) ? null : new TexI(tip);
		    ltip = () -> tt;
		}
		return(ltip);
	    }
	}

	public void tick(double dt) {
	    super.tick(dt);
	    if(spec.spr != null)
		spec.spr.tick(dt);
	}
    }

    public class Input extends SpecWidget {
	public final int idx;
	private List<MenuGrid.Pagina> rpag = null;
	private Coord cc = null;

	public Input(Spec spec, int idx) {
	    super(spec);
	    this.idx = idx;
	}

	public boolean mousedown(Coord c, int button) {
	    if(button == 1) {
		if(rpag == null)
		    Makewindow.this.wdgmsg("findrcps", idx);
		this.cc = c;
		return(true);
	    }
	    return(super.mousedown(c, button));
	}

	public void tick(double dt) {
	    super.tick(dt);
	    if((cc != null) && (rpag != null)) {
		if(!rpag.isEmpty()) {
		    SListMenu.of(UI.scale(250, 120), rpag,
				 pag -> pag.button().name(), pag ->pag.button().img(),
				 pag -> pag.button().use(new MenuGrid.Interaction(1, ui.modflags())))
			.addat(this, cc.add(UI.scale(5, 5))).tick(dt);
		}
		cc = null;
	    }
	}

	public void recipes(List<MenuGrid.Pagina> pag) {
	    rpag = pag;
	}
    }

    public void draw(GOut g) {
	int x = 0;
	if(!qmod.isEmpty()) {
	    g.aimage(qmodl.tex(), new Coord(x, qmy + (qmodsz.y / 2)), 0, 0.5);
	    x += qmodl.sz().x + UI.scale(5);
	    x = Math.max(x, xoff);
	    qmx = x;
	    int count = 0;
	    double product = 1.0;
	    for(Indir<Resource> qm : qmod) {
		try {
		    Tex t = qmicon(qm);
		    g.image(t, new Coord(x, qmy));
		    x += t.sz().x + UI.scale(1);
		    
		    Glob.CAttr attr = ui.gui.chrwdg.findattr(qm.get().basename());
		    if(attr != null) {
			count++;
			product = product * attr.comp;
		    }
		} catch(Loading l) {
		}
	    }
	    if(count > 0) {
		x += drawSoftcap(g, new Coord(x, qmy), product, count);
	    }
	    x += UI.scale(25);
	}
	if(!tools.isEmpty()) {
	    g.aimage(tooll.tex(), new Coord(x, qmy + (qmodsz.y / 2)), 0, 0.5);
	    x += tooll.sz().x + UI.scale(5);
	    x = Math.max(x, xoff);
	    toolx = x;
	    for(Indir<Resource> tool : tools) {
		try {
		    Tex t = qmicon(tool);
		    g.image(t, new Coord(x, qmy));
		    x += t.sz().x + UI.scale(1);
		} catch(Loading l) {
		}
	    }
	    x += UI.scale(25);
	}
	super.draw(g);
    }
    
    private int drawSoftcap(GOut g, Coord p, double product, int count) {
	if(count > 0) {
	    double current = Math.pow(product, 1.0 / count);
	    if(current != softcap || softTex == null) {
		softcap = current;
		String format = String.format("%s %.1f", L10N.label("Softcap:"), softcap);
		Text txt = Text.renderstroked(format, Color.WHITE, Color.BLACK, Glob.CAttr.fnd);
		if(softTex != null) {
		    softTex.dispose();
		}
		softTex = new TexI(txt.img);
	    }
	    g.image(softTex, p.add(UI.scale(5), 0));
	    return softTex.sz().x + UI.scale(6);
	}
	return 0;
    }
    
    private Tex buildQTex(Indir<Resource> res) {
	BufferedImage result = PUtils.convolve(res.get().layer(Resource.imgc).img, qmodsz, CharWnd.iconfilter);
	try {
	    Glob.CAttr attr = ui.gui.chrwdg.findattr(res.get().basename());
	    if(attr != null) {
		result = ItemInfo.catimgsh(1, result, attr.compline().img);
	    }
	} catch (Exception ignored) {
	}
	return new TexI(result);
    }
    
    public static void invalidate(String name) {
	synchronized (qmicons) {
	    LinkedList<Indir<Resource>> tmp = new LinkedList<>(qmicons.keySet());
	    tmp.forEach(res -> {
		if(name.equals(res.get().basename())) {
		    qmicons.remove(res);
		}
	    });
	}
    }
    
    private int qmx, toolx;
    public Object tooltip(Coord mc, Widget prev) {
	String name = null;
	Spec tspec = null;
	Coord c;
	if(!qmod.isEmpty()) {
	    c = new Coord(qmx, qmy);
	    try {
		for(Indir<Resource> qm : qmod) {
		    Tex t = qmicon(qm);
		    Coord sz = t.sz();
		    if(mc.isect(c, sz))
			return(qm.get().flayer(Resource.tooltip).t);
		    c = c.add(sz.x + UI.scale(1), 0);
		}
	    } catch(Loading l) {
	    }
	}
	if(!tools.isEmpty()) {
	    c = new Coord(toolx, qmy);
	    try {
		for(Indir<Resource> tool : tools) {
		    Coord tsz = qmicon(tool).sz();
		    if(mc.isect(c, tsz))
			return(tool.get().flayer(Resource.tooltip).t);
		    c = c.add(tsz.x + UI.scale(1), 0);
		}
	    } catch(Loading l) {
	    }
	}
	return(super.tooltip(mc, prev));
    }

    private static String getDynamicName(GSprite spr) {
	if(spr != null) {
	    Class<? extends GSprite> sprClass = spr.getClass();
	    if(Reflect.hasInterface("haven.res.ui.tt.defn.DynName", sprClass)) {
		return (String) Reflect.invoke(spr, "name");
	    }
	}
	return null;
    }

    public boolean globtype(char ch, java.awt.event.KeyEvent ev) {
	if(ch == '\n') {
	    wdgmsg("make", ui.modctrl?1:0);
	    return(true);
	}
	return(super.globtype(ch, ev));
    }

    public static class Optional extends ItemInfo.Tip {
	public static final Text text = RichText.render(String.format("$i{%s}", L10N.label("Optional")), 0);
	public Optional(Owner owner) {
	    super(owner);
	}

	public BufferedImage tipimg() {
	    return(text.img);
	}

	public Tip shortvar() {return(this);}
    }

    public static class MakePrep extends ItemInfo implements GItem.ColorInfo, GItem.ContentsInfo {
	private final static Color olcol = new Color(0, 255, 0, 64);
	public MakePrep(Owner owner) {
	    super(owner);
	}

	public Color olcol() {
	    return(olcol);
	}

	public void propagate(List<ItemInfo> buf, Owner outer) {
	    if(ItemInfo.find(MakePrep.class, buf) == null)
		buf.add(new MakePrep(outer));
	}
    }
}
