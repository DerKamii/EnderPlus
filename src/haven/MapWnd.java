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

import java.util.*;
import java.util.function.*;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.awt.Color;
import java.awt.event.KeyEvent;
import java.awt.image.*;
import haven.MiniMap.*;
import haven.BuddyWnd.GroupSelector;
import me.ender.minimap.*;

import static haven.MCache.tilesz;
import static haven.MCache.cmaps;
import static haven.Utils.eq;
import javax.swing.JFileChooser;
import javax.swing.filechooser.*;

public class MapWnd extends WindowX implements Console.Directory {
    public static final Resource markcurs = Resource.local().loadwait("gfx/hud/curs/flag");
    public final MapFile file;
    public final MiniMap view;
    public final MapView mv;
    public final Toolbox2 tool;
    public final Collection<String> overlays = new java.util.concurrent.CopyOnWriteArraySet<>();
    public MarkerConfig markcfg = MarkerConfig.showall, cmarkers = null;
    private final Locator player;
    private final Widget toolbar;
    private final Widget topbar;
    private final Frame viewf;
    private GroupSelector colsel;
    protected Button mremove;
    private Button mtrack;
    private Predicate<Marker> mflt = pmarkers;
    private Comparator<ListMarker> mcmp = namecmp;
    private List<ListMarker> markers = Collections.emptyList();
    private int markerseq = -1;
    public boolean domark = false;
    private int olalpha = 64;
    protected final Collection<Runnable> deferred = new LinkedList<>();

    private final static Predicate<Marker> pmarkers = (m -> m instanceof PMarker);
    private final static Predicate<Marker> smarkers = (m -> m instanceof SMarker);
    private final static Predicate<Marker> custmarkers = (m -> m instanceof CustomMarker);
    private final static Comparator<ListMarker> namecmp = ((a, b) -> a.mark.nm.compareTo(b.mark.nm));
    private final static Comparator<ListMarker> typecmp = Comparator.comparing((ListMarker lm) -> lm.type).thenComparing(namecmp);

    public static final KeyBinding kb_home = KeyBinding.get("mapwnd/home", KeyMatch.forcode(KeyEvent.VK_HOME, 0));
    public static final KeyBinding kb_mark = KeyBinding.get("mapwnd/mark", KeyMatch.nil);
    public static final KeyBinding kb_hmark = KeyBinding.get("mapwnd/hmark", KeyMatch.forcode(KeyEvent.VK_M, KeyMatch.C));
    public static final KeyBinding kb_compact = KeyBinding.get("mapwnd/compact", KeyMatch.forchar('A', KeyMatch.M));
    public static final KeyBinding kb_prov = KeyBinding.get("mapwnd/prov", KeyMatch.nil);
    public MapWnd(MapFile file, MapView mv, Coord sz, String title) {
	super(sz, title, true);
	this.file = file;
	this.mv = mv;
	this.player = new MapLocator(mv);
	viewf = add(new ViewFrame());
	view = viewf.add(new View(file));
	recenter();
	toolbar = add(new Widget(Coord.z));
	toolbar.add(new Img(Resource.loadtex("gfx/hud/mmap/fgwdg")) {
		public boolean mousedown(Coord c, int button) {
		    if((button == 1) && checkhit(c)) {
			MapWnd.this.drag(parentpos(MapWnd.this, c));
			return(true);
		    }
		    return(super.mousedown(c, button));
		}
	    }, Coord.z);
	toolbar.add(new IButton("gfx/hud/mmap/home", "", "-d", "-h") {
		{settip("Follow"); setgkey(kb_home);}
		public void click() {
		    recenter();
		}
	    }, Coord.z);
	toolbar.add(new ICheckBox("gfx/hud/mmap/mark", "", "-d", "-h", "-dh"), Coord.z)
	    .state(() -> domark).set(a -> domark = a)
	    .settip("Add marker").setgkey(kb_mark);
	toolbar.add(new ICheckBox("gfx/hud/mmap/hmark", "", "-d", "-h", "-dh"))
	    .state(() -> Utils.eq(markcfg, MarkerConfig.hideall)).click(() -> {
		    if(Utils.eq(markcfg, MarkerConfig.hideall))
			markcfg = MarkerConfig.showall;
		    else if(Utils.eq(markcfg, MarkerConfig.showall) && (cmarkers != null))
			markcfg = cmarkers;
		    else
			markcfg = MarkerConfig.hideall;
		})
	    .settip("Hide markers").setgkey(kb_hmark);
	toolbar.add(new ICheckBox("gfx/hud/mmap/wnd", "", "-d", "-h", "-dh"))
	    .state(() -> decohide()).set(a -> {
		    compact(a);
		    Utils.setprefb("compact-map", a);
		})
	    .settip("Compact mode").setgkey(kb_compact);
	toolbar.add(new ICheckBox("gfx/hud/mmap/prov", "", "-d", "-h", "-dh") {
		public boolean mousewheel(Coord c, int amount) {
		    if(!checkhit(c) || !ui.modshift || !a)
			return(super.mousewheel(c, amount));
		    olalpha = Utils.clip(olalpha + (amount * -32), 32, 256);
		    return(true);
		}
	    })
	    .changed(a -> toggleol("realm", a))
	    .settip("Display provinces").setgkey(kb_prov);
	toolbar.pack();
	topbar = add(new Widget(Coord.z), Coord.z);
    
	Widget btn;
	btn = topbar.add(new ICheckBox("gfx/hud/mmap/view", "", "-d", "-h"))
	    .state(CFG.MMAP_VIEW::get).set(CFG.MMAP_VIEW::set).settip("Display view distance");
    
	btn = topbar.add(new ICheckBox("gfx/hud/mmap/grid", "", "-d", "-h"), btn.pos("ur"))
	    .state(CFG.MMAP_GRID::get).set(CFG.MMAP_GRID::set).settip("Display grid");
    
	btn = topbar.add(new ICheckBox("gfx/hud/mmap/pointer", "", "-d", "-h"), btn.pos("ur"))
	    .state(CFG.MMAP_POINTER::get).set(CFG.MMAP_POINTER::set).settip("Display pointers");
    
	btn = topbar.add(new ICheckBox("gfx/hud/mmap/tile-seek", "", "-d", "-h"), btn.pos("ur"))
	    .changed(a -> toggleol(TileHighlight.TAG, a))
	    .rclick(() -> {TileHighlight.toggle(ui);})
	    .settip("Left-click to toggle tile highlight\nRight-click to open settings", true);
    
	btn = topbar.add(new ICheckBox("gfx/hud/mmap/marknames", "", "-d", "-h"), btn.pos("ur"))
	    .state(CFG.MMAP_SHOW_MARKER_NAMES::get)
	    .set(CFG.MMAP_SHOW_MARKER_NAMES::set)
	    .settip("Show marker names");
	
	topbar.pack();
	tool = add(new Toolbox2());;
	compact(Utils.getprefb("compact-map", false));
	resize(sz);
    }

    public void toggleol(String tag, boolean a) {
	if(a)
	    overlays.add(tag);
	else
	    overlays.remove(tag);
    }

    private class ViewFrame extends Frame {
	Coord sc = Coord.z;

	ViewFrame() {
	    super(Coord.z, true);
	}

	public void resize(Coord sz) {
	    super.resize(sz);
	    sc = sz.sub(box.bisz()).add(box.btloff()).sub(sizer.sz());
	}

	public void draw(GOut g) {
	    super.draw(g);
	    if(decohide())
		g.image(sizer, sc);
	}

	private UI.Grab drag;
	private Coord dragc;
	public boolean mousedown(Coord c, int button) {
	    Coord cc = c.sub(sc);
	    if((button == 1) && decohide() && (cc.x < sizer.sz().x) && (cc.y < sizer.sz().y) && (cc.y >= sizer.sz().y - UI.scale(25) + (sizer.sz().x - cc.x))) {
		if(drag == null) {
		    drag = ui.grabmouse(this);
		    dragc = asz.sub(parentpos(MapWnd.this, c));
		    return(true);
		}
	    }
	    if((button == 1) && (checkhit(c) || ui.modshift)) {
		MapWnd.this.drag(parentpos(MapWnd.this, c));
		return(true);
	    }
	    return(super.mousedown(c, button));
	}

	public void mousemove(Coord c) {
	    if(drag != null) {
		Coord nsz = parentpos(MapWnd.this, c).add(dragc);
		nsz.x = Math.max(nsz.x, UI.scale(150));
		nsz.y = Math.max(nsz.y, UI.scale(150));
		MapWnd.this.resize(nsz);
	    }
	    super.mousemove(c);
	}

	public boolean mouseup(Coord c, int button) {
	    if((button == 1) && (drag != null)) {
		drag.remove();
		drag = null;
		return(true);
	    }
	    return(super.mouseup(c, button));
	}
    }

    private static final int btnw = UI.scale(95);
    public class Toolbox extends Widget {
	public final MarkerList list;
	private final Frame listf;
	private final Button pmbtn, smbtn, nobtn, tobtn, mebtn, mibtn;
	private TextEntry namesel;

	private Toolbox() {
	    super(UI.scale(200, 200));
	    listf = add(new Frame(UI.scale(new Coord(200, 200)), false), 0, 0);
	    list = listf.add(new MarkerList(Coord.of(listf.inner().x, 0)), 0, 0);
	    pmbtn = add(new Button(btnw, "Placed", false) {
		    public void click() {
			mflt = pmarkers;
			markerseq = -1;
		    }
		});
	    smbtn = add(new Button(btnw, "Natural", false) {
		    public void click() {
			mflt = smarkers;
			markerseq = -1;
		    }
		});
	    nobtn = add(new Button(btnw, "By name", false) {
		    public void click() {
			mcmp = namecmp;
			markerseq = -1;
		    }
		});
	    tobtn = add(new Button(btnw, "By type", false) {
		    public void click() {
			mcmp = typecmp;
			markerseq = -1;
		    }
		});
	    mebtn = add(new Button(btnw, "Export...", false) {
		    public void click() {
			exportmap();
		    }
		});
	    mibtn = add(new Button(btnw, "Import...", false) {
		    public void click() {
			importmap();
		    }
		});
	}

	public void resize(int h) {
	    super.resize(new Coord(sz.x, h));
	    listf.resize(listf.sz.x, sz.y - UI.scale(210));
	    listf.c = new Coord(sz.x - listf.sz.x, 0);
	    list.resize(listf.inner());
	    mebtn.c = new Coord(0, sz.y - mebtn.sz.y);
	    mibtn.c = new Coord(sz.x - btnw, sz.y - mibtn.sz.y);
	    nobtn.c = new Coord(0, mebtn.c.y - UI.scale(30) - nobtn.sz.y);
	    tobtn.c = new Coord(sz.x - btnw, mibtn.c.y - UI.scale(30) - tobtn.sz.y);
	    pmbtn.c = new Coord(0, nobtn.c.y - UI.scale(5) - pmbtn.sz.y);
	    smbtn.c = new Coord(sz.x - btnw, tobtn.c.y - UI.scale(5) - smbtn.sz.y);
	    if(namesel != null) {
		namesel.c = listf.c.add(0, listf.sz.y + UI.scale(10));
		if(colsel != null) {
		    colsel.c = namesel.c.add(0, namesel.sz.y + UI.scale(10));
//		    mremove.c = colsel.c.add(0, colsel.sz.y + UI.scale(10));
		}
		int y = namesel.sz.y + BuddyWnd.margin3 + UI.scale(20);
		mremove.c = namesel.c.add(0, y);
		mtrack.c = namesel.c.add(UI.scale(105), y);
	    }
	}
    }

    private class View extends MiniMap {
        private double a = 0;
        
	View(MapFile file) {
	    super(file);
	    big = true;
	}

	public void drawgrid(GOut g, Coord ul, DisplayGrid disp) {
	    super.drawgrid(g, ul, disp);
	    for(String tag : overlays) {
		try {
		    int alpha = olalpha;
		    Tex img;
		    if(TileHighlight.TAG.equals(tag)) {
			alpha = (int) (100 + 155 * a);
			img = disp.tileimg();
		    } else {
			img = disp.olimg(tag);
			
		    }
		    if(img != null) {
			g.chcolor(255, 255, 255, alpha);
			g.image(img, ul, UI.scale(img.sz()).mul(scale));
		    }
		} catch(Loading l) {
		}
	    }
	    g.chcolor();
	}

	public boolean filter(DisplayMarker mark) {
	    return(markcfg.filter(mark.m));
	}

	public boolean clickmarker(DisplayMarker mark, Location loc, int button, boolean press) {
	    if(button == 1) {
		if(!decohide() && !press && !domark) {
		    focus(mark.m);
		    return(true);
		}
	    } else if(mark.m instanceof SMarker) {
		Gob gob = MarkerID.find(ui.sess.glob.oc, ((SMarker)mark.m).oid);
		if(gob != null)
		    mvclick(mv, null, loc, gob, button);
	    }
	    return(false);
	}

	public boolean clickicon(DisplayIcon icon, Location loc, int button, boolean press) {
	    if(!press && !domark) {
		mvclick(mv, null, loc, icon.gob, button);
		return(true);
	    }
	    return(false);
	}

	public boolean clickloc(Location loc, int button, boolean press) {
	    if(domark && (button == 1) && !press) {
		Marker nm = new PMarker(loc.seg.id, loc.tc, "New marker", BuddyWnd.gc[new Random().nextInt(BuddyWnd.gc.length)]);
		file.add(nm);
		focus(nm);
		domark = false;
		return(true);
	    }
	    if(!press && (sessloc != null) && (loc.seg == sessloc.seg)) {
		mvclick(mv, null, loc, null, button);
		return(true);
	    }
	    return(false);
	}

	public boolean mousedown(Coord c, int button) {
	    if(domark && (button == 3)) {
		domark = false;
		return(true);
	    }
	    super.mousedown(c, button);
	    return(true);
	}

	public void draw(GOut g) {
	    g.chcolor(0, 0, 0, 128);
	    g.frect(Coord.z, sz);
	    g.chcolor();
	    super.draw(g);
	}

	public Resource getcurs(Coord c) {
	    if(domark)
		return(markcurs);
	    return(super.getcurs(c));
	}
    
	@Override
	public void tick(double dt) {
	    super.tick(dt);
	    a = Math.sin(Math.PI * ((System.currentTimeMillis() % 1000) / 1000.0));
	}
    }

    public void tick(double dt) {
	super.tick(dt);
	synchronized(deferred) {
	    for(Iterator<Runnable> i = deferred.iterator(); i.hasNext();) {
		Runnable task = i.next();
		try {
		    task.run();
		} catch(Loading l) {
		    continue;
		}
		i.remove();
	    }
	}
	if(visible && (markerseq != view.file.markerseq)) {
	    if(view.file.lock.readLock().tryLock()) {
		try {
		    Map<Marker, ListMarker> prev = new HashMap<>();
		    for(ListMarker pm : this.markers)
			prev.put(pm.mark, pm);
		    List<ListMarker> markers = new ArrayList<>();
		    for(Marker mark : view.file.markers) {
			if(!mflt.test(mark))
			    continue;
			ListMarker lm = prev.get(mark);
			if(lm == null)
			    lm = new ListMarker(mark);
			else
			    lm.type = MarkerType.of(lm.mark);
			markers.add(lm);
		    }
		    markers.sort(mcmp);
		    this.markers = markers;
		} finally {
		    view.file.lock.readLock().unlock();
		}
	    }
	}
    }

    public static abstract class MarkerType implements Comparable<MarkerType> {
	public static final int iconsz = UI.scale(20);
	private static final HashedSet<MarkerType> types = new HashedSet<>(Hash.eq);
	public abstract Tex icon();

	public static MarkerType of(Marker mark) {
	    if(mark instanceof PMarker) {
		return(types.intern(new PMarkerType(((PMarker)mark).color)));
	    } else if(mark instanceof SMarker) {
		return(types.intern(new SMarkerType(((SMarker)mark).res)));
	    } else if(mark instanceof CustomMarker) {
		CustomMarker cmark = (CustomMarker) mark;
		return(types.intern(new CustomMarkerType(cmark.color, cmark.res)));
	    } else {
		return(null);
	    }
	}

	public int compareTo(MarkerType that) {
	    return(this.getClass().getName().compareTo(that.getClass().getName()));
	}
    }

    public static class PMarkerType extends MarkerType {
	public final Color col;
	private Tex icon = null;

	public PMarkerType(Color col) {
	    this.col = col;
	}

	public Tex icon() {
	    if(icon == null) {
		Resource.Image fg = MiniMap.DisplayMarker.flagfg, bg = MiniMap.DisplayMarker.flagbg;
		WritableRaster buf = PUtils.imgraster(new Coord(Math.max(fg.o.x + fg.sz.x, bg.o.x + bg.sz.x),
								Math.max(fg.o.y + fg.sz.y, bg.o.y + bg.sz.y)));
		PUtils.blit(buf, PUtils.coercergba(fg.img).getRaster(), fg.o);
		PUtils.colmul(buf, col);
		PUtils.alphablit(buf, PUtils.coercergba(bg.img).getRaster(), bg.o);
		icon = new TexI(PUtils.uiscale(PUtils.rasterimg(buf), new Coord(iconsz, iconsz)));
	    }
	    return(icon);
	}

	public boolean equals(PMarkerType that) {
	    return(Utils.eq(this.col, that.col));
	}
	public boolean equals(Object that) {
	    return((that instanceof PMarkerType) && equals((PMarkerType)that));
	}

	public int hashCode() {
	    return(col.hashCode());
	}

	public int compareTo(PMarkerType that) {
	    int a = Utils.index(BuddyWnd.gc, this.col), b = Utils.index(BuddyWnd.gc, that.col);
	    if((a >= 0) && (b >= 0))
		return(a - b);
	    if((a < 0) && (b >= 0))
		return(1);
	    if((a >= 0) && (b < 0))
		return(-1);
	    return(Utils.idcmp.compare(this.col, that.col));
	}
	public int compareTo(MarkerType that) {
	    if(that instanceof PMarkerType)
		return(compareTo((PMarkerType)that));
	    return(super.compareTo(that));
	}
    }

    public static class SMarkerType extends MarkerType {
	private Resource.Spec spec;
	private Tex icon = null;

	public SMarkerType(Resource.Spec spec) {
	    this.spec = spec;
	}

	public Tex icon() {
	    if(icon == null) {
		BufferedImage img = spec.loadsaved().flayer(Resource.imgc).img;
		icon = new TexI(PUtils.uiscale(img, new Coord((iconsz * img.getWidth())/ img.getHeight(), iconsz)));
	    }
	    return(icon);
	}

	public boolean equals(SMarkerType that) {
	    if(Utils.eq(this.spec.name, that.spec.name)) {
		if(that.spec.ver > this.spec.ver) {
		    this.spec = that.spec;
		    this.icon = null;
		}
		return(true);
	    }
	    return(false);
	}
	public boolean equals(Object that) {
	    return((that instanceof SMarkerType) && equals((SMarkerType)that));
	}

	public int hashCode() {
	    return(spec.name.hashCode());
	}

	public int compareTo(SMarkerType that) {
	    return(this.spec.name.compareTo(that.spec.name));
	}
	public int compareTo(MarkerType that) {
	    if(that instanceof SMarkerType)
		return(compareTo((SMarkerType)that));
	    return(super.compareTo(that));
	}
    }
    
    public static class CustomMarkerType extends MarkerType {
	private final Resource.Spec spec;
	public final Color col;
	private CustomMarker.Image icon = null;
	
	public CustomMarkerType(Color col, Resource.Spec spec) {
	    this.col = col;
	    this.spec = spec;
	}
	
	public Tex icon() {
	    if(icon == null) {
		icon = CustomMarker.image(spec, col);
	    }
	    return icon != null ? icon.tex : null;
	}
	
	public boolean equals(CustomMarkerType that) {
	    return(Utils.eq(this.col, that.col) && Utils.eq(this.spec, that.spec));
	}
	public boolean equals(Object that) {
	    return((that instanceof CustomMarkerType) && equals((CustomMarkerType)that));
	}
	
	public int hashCode() {
	    return Objects.hash(col.hashCode(), spec);
	}
	
	public int compareTo(CustomMarkerType that) {
	    int byRes = this.spec.name.compareTo(that.spec.name);
	    if(byRes != 0) {return byRes;}
	    
	    int a = Utils.index(BuddyWnd.gc, this.col), b = Utils.index(BuddyWnd.gc, that.col);
	    if((a >= 0) && (b >= 0))
		return(a - b);
	    if((a < 0) && (b >= 0))
		return(1);
	    if((a >= 0) && (b < 0))
		return(-1);
	    return(Utils.idcmp.compare(this.col, that.col));
	}
	public int compareTo(MarkerType that) {
	    if(that instanceof CustomMarkerType)
		return(compareTo((CustomMarkerType)that));
	    return(super.compareTo(that));
	}
    }

    public static class MarkerConfig {
	public static final MarkerConfig showall = new MarkerConfig();
	public static final MarkerConfig hideall = new MarkerConfig().showsel(true);
	public Set<MarkerType> sel = Collections.emptySet();
	public boolean showsel = false;

	public MarkerConfig() {
	}

	public MarkerConfig(MarkerConfig from) {
	    this.sel = from.sel;
	    this.showsel = from.showsel;
	}

	public MarkerConfig showsel(boolean showsel) {
	    MarkerConfig ret = new MarkerConfig(this);
	    ret.showsel = showsel;
	    return(ret);
	}

	public MarkerConfig add(MarkerType type) {
	    MarkerConfig ret = new MarkerConfig(this);
	    ret.sel = new HashSet<>(ret.sel);
	    ret.sel.add(type);
	    return(ret);
	}

	public MarkerConfig remove(MarkerType type) {
	    MarkerConfig ret = new MarkerConfig(this);
	    ret.sel = new HashSet<>(ret.sel);
	    ret.sel.remove(type);
	    return(ret);
	}

	public MarkerConfig toggle(MarkerType type) {
	    if(sel.contains(type))
		return(remove(type));
	    else
		return(add(type));
	}

	public boolean filter(MarkerType type) {
	    return(sel.contains(type) != showsel);
	}

	public boolean filter(Marker mark) {
	    return(sel.isEmpty() ? showsel : filter(MarkerType.of(mark)));
	}

	public boolean equals(MarkerConfig that) {
	    return(Utils.eq(this.sel, that.sel) && (this.showsel == that.showsel));
	}
	public boolean equals(Object that) {
	    return((that instanceof MarkerConfig) && equals((MarkerConfig)that));
	}
    }

    public static class ListMarker {
	public final Marker mark;
	public MarkerType type;

	public ListMarker(Marker mark) {
	    this.mark = mark;
	    type = MarkerType.of(mark);
	}
    }

    public class MarkerList extends SSearchBox<ListMarker, Widget> {
	public MarkerList(Coord sz) {
	    super(sz, MarkerType.iconsz);
	}

	public List<ListMarker> allitems() {return(markers);}
	public boolean searchmatch(ListMarker lm, String txt) {return(lm.mark.nm.toLowerCase().indexOf(txt.toLowerCase()) >= 0);}

	public Widget makeitem(ListMarker lm, int idx, Coord sz) {
	    Widget ret = new ItemWidget<ListMarker>(this, sz, lm);
	    ret.add(new IconText(sz) {
		    protected BufferedImage img() {throw(new RuntimeException());}
		    protected String text() {return(lm.mark.nm);}
		    protected boolean valid(String text) {return(Utils.eq(text, text()));}
		
		    protected void drawicon(GOut g) {
			try {
			    Tex icon = lm.type.icon();
			    if(icon == null) {return;}
			    if(markcfg.filter(lm.type))
				g.chcolor(255, 255, 255, 128);
			    g.aimage(icon, Coord.of(sz.y / 2), 0.5, 0.5);
			    g.chcolor();
			} catch(Loading l) {
			}
		    }

		    public boolean mousedown(Coord c, int button) {
			if(c.x < sz.y) {
			    toggletype(lm.type);
			    return(true);
			}
			return(super.mousedown(c, button));
		    }
		}, Coord.z);
	    return(ret);
	}
    
	private void toggletype(MarkerType type) {
	    MarkerConfig nc = markcfg.toggle(type);
	    markcfg = nc;
	    cmarkers = nc.sel.isEmpty() ? null : nc;
	}

	public void change(ListMarker lm) {
	    change2(lm);
	    if(lm != null)
		view.center(new SpecLocator(lm.mark.seg, lm.mark.tc));
	}

	public void change2(ListMarker lm) {
	    this.sel = lm;
	    change3(lm != null ? lm.mark : null);
	}
	
	public void change3(Marker mark) {
	    if(tool.namesel != null) {
		ui.destroy(tool.namesel);
		tool.namesel = null;
		if(colsel != null) {
		    ui.destroy(colsel);
		    colsel = null;
		}
		if(mremove != null) {
		    ui.destroy(mremove);
		    mremove = null;
		}
		if(mtrack != null) {
		    ui.destroy(mtrack);
		    mtrack = null;
		}
	    }

	    if(mark != null) {
		if(tool.namesel == null) {
		    tool.namesel = tool.add(new TextEntry(UI.scale(200), "") {
			    {dshow = true;}
			    public void activate(String text) {
				mark.nm = text;
				view.file.update(mark);
				commit();
				change2(null);
			    }
			});
		}
		tool.namesel.settext(mark.nm);
		tool.namesel.buf.point(mark.nm.length());
		tool.namesel.commit();
		if(mark instanceof PMarker) {
		    PMarker pm = (PMarker)mark;
		    colsel = tool.add(new GroupSelector(Math.max(0, Utils.index(BuddyWnd.gc, pm.color))) {
			    public void changed(int group) {
				pm.color = BuddyWnd.gc[group];
				view.file.update(mark);
			    }
			});
		} else if(mark instanceof CustomMarker) {
		    CustomMarker cm = (CustomMarker) mark;
		    colsel = tool.add(new GroupSelector(Math.max(0, Utils.index(BuddyWnd.gc, cm.color))) {
			public void changed(int group) {
			    cm.color = BuddyWnd.gc[group];
			    view.file.update(mark);
			}
		    });
		}
		mremove = tool.add(new Button(UI.scale(95), "Remove", false) {
		    public void click() {
			view.file.remove(mark);
			ui.gui.untrack(mark);
			change2(null);
		    }
		});
		mtrack = tool.add(new Button(UI.scale(95), ui.gui.isTracked(mark) ? "Untrack" : "Track", false) {
		    public void click() {
			if(ui.gui.isTracked(mark)) {
			    ui.gui.untrack(mark);
			    change("Track");
			} else {
			    ui.gui.track(mark);
			    change("Unrack");
			}
		    }
		});
		MapWnd.this.resize(asz);
	    }
	}
    }

    public void resize(Coord sz) {
	super.resize(sz);
	tool.resize(sz.y);
	if(!decohide()) {
	    tool.c = new Coord(sz.x - tool.sz.x, 0);
	    viewf.resize(tool.pos("bl").subs(10, 0));
	} else {
	    viewf.resize(sz);
	    tool.c = viewf.pos("ur").adds(10, 0);
	}
	view.resize(viewf.inner());
	toolbar.c = viewf.c.add(0, viewf.sz.y - toolbar.sz.y).add(UI.scale(2), UI.scale(-2));
    }

    public void compact(boolean a) {
	tool.show(!a);
	if(a)
	    delfocusable(tool);
	else
	    newfocusable(tool);
	decohide(a);
	pack();
    }

    public void recenter() {
	view.follow(player);
    }

    public void focus(Marker m) {
	for(ListMarker lm : markers) {
	    if(lm.mark == m) {
		tool.list.change2(lm);
		tool.list.display(lm);
		return;
	    }
	}
	tool.list.change2(null);
	tool.list.change3(m);
    }

    protected void drawframe(GOut g) {
	g.image(sizer, ctl.add(csz).sub(sizer.sz()));
	super.drawframe(g);
    }

    private UI.Grab drag;
    private Coord dragc;
    public boolean mousedown(Coord c, int button) {
	Coord cc = c.sub(ctl);
	if((button == 1) && (cc.x < csz.x) && (cc.y < csz.y) && (cc.y >= csz.y - UI.scale(25) + (csz.x - cc.x))) {
	    if(drag == null) {
		drag = ui.grabmouse(this);
		dragc = asz.sub(c);
		return(true);
	    }
	}
	return(super.mousedown(c, button));
    }

    public void mousemove(Coord c) {
	if(drag != null) {
	    Coord nsz = c.add(dragc);
	    nsz.x = Math.max(nsz.x, UI.scale(350));
	    nsz.y = Math.max(nsz.y, UI.scale(240));
	    resize(nsz);
	}
	super.mousemove(c);
    }

    public boolean mouseup(Coord c, int button) {
	if((button == 1) && (drag != null)) {
	    drag.remove();
	    drag = null;
	    return(true);
	}
	return(super.mouseup(c, button));
    }

    public void markobj(long gobid, long oid, Indir<Resource> resid, String nm) {
	synchronized(deferred) {
	    deferred.add(new Runnable() {
		    double f = 0;
		    public void run() {
			Resource res = resid.get();
			String rnm = nm;
			if(rnm == null) {
			    Resource.Tooltip tt = res.layer(Resource.tooltip);
			    if(tt == null)
				return;
			    rnm = tt.t;
			}
			double now = Utils.rtime();
			if(f == 0)
			    f = now;
			Gob gob = ui.sess.glob.oc.getgob(gobid);
			if(gob == null) {
			    if(now - f < 1.0)
				throw(new Loading());
			    return;
			}
			synchronized(gob) {
			    gob.setattr(new MarkerID(gob, oid));
			}
			Coord tc = gob.rc.floor(tilesz);
			MCache.Grid obg = ui.sess.glob.map.getgrid(tc.div(cmaps));
			if(!view.file.lock.writeLock().tryLock())
			    throw(new Loading());
			try {
			    MapFile.GridInfo info = view.file.gridinfo.get(obg.id);
			    if(info == null)
				throw(new Loading());
			    Coord sc = tc.add(info.sc.sub(obg.gc).mul(cmaps));
			    SMarker prev = view.file.smarkers.get(oid);
			    if(prev == null) {
				view.file.add(new SMarker(info.seg, sc, rnm, oid, new Resource.Spec(Resource.remote(), res.name, res.ver)));
			    } else {
				if((prev.seg != info.seg) || !eq(prev.tc, sc) || !eq(prev.nm, rnm)) {
				    prev.seg = info.seg;
				    prev.tc = sc;
				    prev.nm = rnm;
				    view.file.update(prev);
				}
			    }
			} finally {
			    view.file.lock.writeLock().unlock();
			}
		    }
		});
	}
    }

    public static class ExportWindow extends WindowX implements MapFile.ExportStatus {
	private Thread th;
	private volatile String prog = "Exporting map...";

	public ExportWindow() {
	    super(UI.scale(new Coord(300, 65)), "Exporting map...", true);
	    adda(new Button(UI.scale(100), "Cancel", false, this::cancel), asz.x / 2, UI.scale(40), 0.5, 0.0);
	}

	public void run(Thread th) {
	    (this.th = th).start();
	}

	public void cdraw(GOut g) {
	    g.text(prog, UI.scale(new Coord(10, 10)));
	}

	public void cancel() {
	    th.interrupt();
	}

	public void tick(double dt) {
	    if(!th.isAlive())
		destroy();
	}

	public void grid(int cs, int ns, int cg, int ng) {
	    this.prog = String.format("Exporting map cut %,d/%,d in segment %,d/%,d", cg, ng, cs, ns);
	}

	public void mark(int cm, int nm) {
	    this.prog = String.format("Exporting marker", cm, nm);
	}
    }

    public static class ImportWindow extends WindowX {
	private Thread th;
	private volatile String prog = "Initializing";
	private double sprog = -1;

	public ImportWindow() {
	    super(UI.scale(new Coord(300, 65)), "Importing map...", true);
	    adda(new Button(UI.scale(100), "Cancel", false, this::cancel), asz.x / 2, UI.scale(40), 0.5, 0.0);
	}

	public void run(Thread th) {
	    (this.th = th).start();
	}

	public void cdraw(GOut g) {
	    String prog = this.prog;
	    if(sprog >= 0)
		prog = String.format("%s: %d%%", prog, (int)Math.floor(sprog * 100));
	    else
		prog = prog + "...";
	    g.text(prog, UI.scale(new Coord(10, 10)));
	}

	public void cancel() {
	    th.interrupt();
	}

	public void tick(double dt) {
	    if(!th.isAlive())
		destroy();
	}

	public void prog(String prog) {
	    this.prog = prog;
	    this.sprog = -1;
	}

	public void sprog(double sprog) {
	    this.sprog = sprog;
	}
    }

    public void exportmap(Path path) {
	GameUI gui = getparent(GameUI.class);
	ExportWindow prog = new ExportWindow();
	Thread th = new HackThread(() -> {
		boolean complete = false;
		try {
		    try {
			try(OutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
			    file.export(out, MapFile.ExportFilter.all, prog);
			}
			complete = true;
		    } finally {
			if(!complete)
			    Files.deleteIfExists(path);
		    }
		} catch(IOException e) {
		    e.printStackTrace(Debug.log);
		    gui.error("Unexpected error occurred when exporting map.");
		} catch(InterruptedException e) {
		}
	}, "Mapfile exporter");
	prog.run(th);
	gui.adda(prog, gui.sz.div(2), 0.5, 1.0);
    }

    public void importmap(Path path) {
	GameUI gui = getparent(GameUI.class);
	ImportWindow prog = new ImportWindow();
	Thread th = new HackThread(() -> {
		try {
		    try(SeekableByteChannel fp = Files.newByteChannel(path)) {
			long size = fp.size();
			class Updater extends CountingInputStream {
			    Updater(InputStream bk) {super(bk);}

			    protected void update(long val) {
				super.update(val);
				prog.sprog((double)pos / (double)size);
			    }
			}
			prog.prog("Validating map data");
			file.reimport(new Updater(new BufferedInputStream(Channels.newInputStream(fp))), MapFile.ImportFilter.readonly);
			prog.prog("Importing map data");
			fp.position(0);
			file.reimport(new Updater(new BufferedInputStream(Channels.newInputStream(fp))), MapFile.ImportFilter.all);
		    }
		} catch(InterruptedException e) {
		} catch(Exception e) {
		    e.printStackTrace(Debug.log);
		    gui.error("Could not import map: " + e.getMessage());
		}
	}, "Mapfile importer");
	prog.run(th);
	gui.adda(prog, gui.sz.div(2), 0.5, 1.0);
    }

    public void exportmap() {
	java.awt.EventQueue.invokeLater(() -> {
		JFileChooser fc = new JFileChooser();
		fc.setFileFilter(new FileNameExtensionFilter("Exported Haven map data", "hmap"));
		if(fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION)
		    return;
		Path path = fc.getSelectedFile().toPath();
		if(path.getFileName().toString().indexOf('.') < 0)
		    path = path.resolveSibling(path.getFileName() + ".hmap");
		exportmap(path);
	    });
    }

    public void importmap() {
	java.awt.EventQueue.invokeLater(() -> {
		JFileChooser fc = new JFileChooser();
		fc.setFileFilter(new FileNameExtensionFilter("Exported Haven map data", "hmap"));
		if(fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION)
		    return;
		importmap(fc.getSelectedFile().toPath());
	    });
    }
    
    public Coord2d findMarkerPosition(String name) {
	Location sessloc = view.sessloc;
	if(sessloc == null) {return null;}
	for (Map.Entry<Long, SMarker> e : file.smarkers.entrySet()) {
	    SMarker m = e.getValue();
	    if(m.seg == sessloc.seg.id && m.nm.contains(name)) {
		return m.tc.sub(sessloc.tc).mul(tilesz);
	    }
	}
	return null;
    }
    
    public long playerSegmentId() {
	Location sessloc = view.sessloc;
	if(sessloc == null) {return 0;}
	return sessloc.seg.id;
    }
    
    public Location playerLocation() {
	return view.sessloc;
    }
    
    enum MarkerCategory {
	placed("Placed", pmarkers), natural("Natural", smarkers), custom("Custom", custmarkers);
	
	private final String name;
	private final Predicate<Marker> filter;
	
	MarkerCategory(String name, Predicate<Marker> filter) {
	    this.name = name;
	    this.filter = filter;
	}
    }
    
    enum MarkerSorting {
	name("Name", namecmp), type("Type", typecmp);
	
	private final String label;
	private final Comparator<ListMarker> comparator;
	
	MarkerSorting(String label, Comparator<ListMarker> cmp) {
	    this.label = label;
	    this.comparator = cmp;
	}
    }
    
    public class Toolbox2 extends Widget {
	public final MarkerList list;
	private final Frame listf;
	private final Button mebtn, mibtn;
	private final Dropbox<MarkerCategory> categories;
	private final Dropbox<MarkerSorting> sorting;
	private TextEntry namesel;
	private final Coord cat_c = UI.scale(3, 8);
	private final int sort_w = UI.scale(75);
	
	private Toolbox2() {
	    super(UI.scale(200, 200));
	    listf = add(new Frame(UI.scale(new Coord(200, 200)), false), 0, 0);
	    list = listf.add(new MarkerList(new Coord(listf.inner().x, 0)), 0, 0);
	    
	    categories = add(new Dropbox<MarkerCategory>(UI.scale(100), MarkerCategory.values().length, UI.scale(16)) {
		@Override
		protected MarkerCategory listitem(int i) {
		    return MarkerCategory.values()[i];
		}
		
		@Override
		protected int listitems() {
		    return MarkerCategory.values().length;
		}
		
		@Override
		protected void drawitem(GOut g, MarkerCategory item, int i) {
		    g.atext(item.name, cat_c, 0, 0.5);
		}
		
		@Override
		public void change(MarkerCategory item) {
		    super.change(item);
		    mflt = item.filter;
		    markerseq = -1;
		}
	    });
	    categories.change(MarkerCategory.placed);
	    
	    sorting = add(new Dropbox<MarkerSorting>(sort_w, MarkerSorting.values().length, UI.scale(16)) {
		@Override
		protected MarkerSorting listitem(int i) {
		    return MarkerSorting.values()[i];
		}
		
		@Override
		protected int listitems() {
		    return MarkerSorting.values().length;
		}
		
		@Override
		protected void drawitem(GOut g, MarkerSorting item, int i) {
		    g.atext(item.label, cat_c, 0, 0.5);
		}
		
		@Override
		public void change(MarkerSorting item) {
		    super.change(item);
		    mcmp = item.comparator;
		    markerseq = -1;
		}
	    });
	    sorting.change(MarkerSorting.name);
	    
	    mebtn = add(new Button(btnw, "Export...", false) {
		public void click() {
		    exportmap();
		}
	    });
	    mibtn = add(new Button(btnw, "Import...", false) {
		public void click() {
		    importmap();
		}
	    });
	}
	
	public void resize(int h) {
	    super.resize(new Coord(sz.x, h));
	    categories.c = new Coord(UI.scale(3), 0);
	    sorting.c = new Coord(sz.x - sort_w - UI.scale(3), 0);
	    listf.resize(listf.sz.x, sz.y - UI.scale(150));
	    listf.c = new Coord(sz.x - listf.sz.x, categories.sz.y + UI.scale(3));
	    list.resize(listf.inner());
	    mebtn.c = new Coord(0, sz.y - mebtn.sz.y - UI.scale(5));
	    mibtn.c = new Coord(sz.x - btnw, sz.y - mibtn.sz.y - UI.scale(5));
	    if(namesel != null) {
		namesel.c = listf.c.add(0, listf.sz.y + UI.scale(10));
		if(colsel != null) {
		    colsel.c = namesel.c.add(0, namesel.sz.y + UI.scale(10));
		}
		int y = namesel.sz.y + BuddyWnd.margin3 + UI.scale(20);
		mremove.c = namesel.c.add(0, y);
		mtrack.c = namesel.c.add(UI.scale(105), y);
	    }
	}
    }
    
    private Map<String, Console.Command> cmdmap = new TreeMap<String, Console.Command>();
    {
	cmdmap.put("exportmap", new Console.Command() {
		public void run(Console cons, String[] args) {
		    if(args.length > 1)
			exportmap(Utils.path(args[1]));
		    else
			exportmap();
		}
	    });
	cmdmap.put("importmap", new Console.Command() {
		public void run(Console cons, String[] args) {
		    if(args.length > 1)
			importmap(Utils.path(args[1]));
		    else
			importmap();
		}
	    });
    }
    public Map<String, Console.Command> findcmds() {
	return(cmdmap);
    }
}
