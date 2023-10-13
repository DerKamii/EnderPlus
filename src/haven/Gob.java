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

import java.awt.*;
import java.util.*;
import java.util.function.*;
import haven.render.*;
import haven.res.gfx.fx.msrad.MSRad;
import integrations.mapv4.MappingClient;
import me.ender.minimap.AutoMarkers;

import static haven.OCache.*;

public class Gob implements RenderTree.Node, Sprite.Owner, Skeleton.ModOwner, EquipTarget, Skeleton.HasPose {
    private static final Color COL_READY = new Color(16, 255, 16, 128);
    private static final Color COL_FULL = new Color(215, 63, 250, 64);
    private static final Color COL_EMPTY = new Color(104, 213, 253, 64);
    public Coord2d rc;
    public double a;
    public boolean virtual = false;
    int clprio = 0;
    public long id;
    public boolean removed = false;
    public final Glob glob;
    private boolean disposed = false;
    final Map<Class<? extends GAttrib>, GAttrib> attr = new HashMap<Class<? extends GAttrib>, GAttrib>();
    public final Collection<Overlay> ols = new ArrayList<Overlay>();
    public final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
    public int updateseq = 0;
    private final Collection<SetupMod> setupmods = new ArrayList<>();
    private final LinkedList<Runnable> deferred = new LinkedList<>();
    private Loader.Future<?> deferral = null;
    private final Object removalLock = new Object();
    private GobDamageInfo damage;
    private HidingGobSprite<Hitbox> hitbox = null;
    public Drawable drawable;
    public Moving moving;
    private Boolean isMe = null;
    private final GeneralGobInfo info;
    private GobWarning warning = null;
    public StatusUpdates status = new StatusUpdates();
    private final CustomColor customColor = new CustomColor();
    private final Set<GobTag> tags = new HashSet<>();
    public boolean drivenByPlayer = false;
    public boolean mapProcessed = false;
    public long drives = 0;
    private GobRadius radius = null;
    private long eseq = 0;
    public static final ChangeCallback CHANGED = new ChangeCallback() {
	@Override
	public void added(Gob ob) {
	    
	}
	
	@Override
	public void removed(Gob ob) {
	    ob.dispose();
	}
    };

    public static class Overlay implements RenderTree.Node {
	public final int id;
	public final Gob gob;
	public final Indir<Resource> res;
	public MessageBuf sdt;
	public Sprite spr;
	public boolean delign = false;
	private Collection<RenderTree.Slot> slots = null;
	private boolean added = false;

	public Overlay(Gob gob, int id, Indir<Resource> res, Message sdt) {
	    this.gob = gob;
	    this.id = id;
	    this.res = res;
	    this.sdt = new MessageBuf(sdt);
	    this.spr = null;
	}

	public Overlay(Gob gob, Sprite spr) {
	    this.gob = gob;
	    this.id = -1;
	    this.res = null;
	    this.sdt = null;
	    this.spr = spr;
	}

	private void init() {
	    if(spr == null) {
		spr = Sprite.create(gob, res.get(), sdt);
		if(added && (spr instanceof SetupMod))
		    gob.setupmods.add((SetupMod)spr);
	    }
	    if(slots == null)
		RUtils.multiadd(gob.slots, this);
	}

	private void add0() {
	    if(added)
		throw(new IllegalStateException());
	    if(spr instanceof SetupMod)
		gob.setupmods.add((SetupMod)spr);
	    added = true;
	}

	private void remove0() {
	    if(!added)
		throw(new IllegalStateException());
	    if(slots != null) {
		RUtils.multirem(new ArrayList<>(slots));
		slots = null;
	    }
	    if(spr instanceof SetupMod)
		gob.setupmods.remove(spr);
	    added = false;
	}

	public void remove(boolean async) {
	    if(async) {
		gob.defer(() -> remove(false));
		return;
	    }
	    remove0();
	    gob.ols.remove(this);
	    gob.overlaysUpdated();
	}

	public void remove() {
	    remove(true);
	}

	public void added(RenderTree.Slot slot) {
	    slot.add(spr);
	    if(slots == null)
		slots = new ArrayList<>(1);
	    slots.add(slot);
	}

	public void removed(RenderTree.Slot slot) {
	    if(slots != null)
		slots.remove(slot);
	}
    }
    
    private static class CustomColor implements SetupMod {
	Pipe.Op op = null;
	Color c = null;
    
	boolean color(Color c) {
	    boolean changed = !Objects.equals(c, this.c);
	    if(c == null) {
		op = null;
	    } else {
		op = new MixColor(c);
	    }
	    this.c = c;
	    return changed;
	}
    
	@Override
	public Pipe.Op gobstate() {
	    return op;
	}
    }

    public static interface SetupMod {
	public default Pipe.Op gobstate() {return(null);}
	public default Pipe.Op placestate() {return(null);}
    }

    public static interface Placer {
	/* XXX: *Quite* arguably, the distinction between getc and
	 * getr should be abolished and a single transform matrix
	 * should be used instead, but that requires first abolishing
	 * the distinction between the gob/gobx location IDs. */
	public Coord3f getc(Coord2d rc, double ra);
	public Matrix4f getr(Coord2d rc, double ra);
    }

    public static interface Placing {
	public Placer placer();
    }

    public static class DefaultPlace implements Placer {
	public final MCache map;
	public final MCache.SurfaceID surf;

	public DefaultPlace(MCache map, MCache.SurfaceID surf) {
	    this.map = map;
	    this.surf = surf;
	}

	public Coord3f getc(Coord2d rc, double ra) {
	    return(map.getzp(surf, rc));
	}

	public Matrix4f getr(Coord2d rc, double ra) {
	    return(Transform.makerot(new Matrix4f(), Coord3f.zu, -(float)ra));
	}
    }

    public static class InclinePlace extends DefaultPlace {
	public InclinePlace(MCache map, MCache.SurfaceID surf) {
	    super(map, surf);
	}

	public Matrix4f getr(Coord2d rc, double ra) {
	    Matrix4f ret = super.getr(rc, ra);
	    Coord3f norm = map.getnorm(surf, rc);
	    norm.y = -norm.y;
	    Coord3f rot = Coord3f.zu.cmul(norm);
	    float sin = rot.abs();
	    if(sin > 0) {
		Matrix4f incl = Transform.makerot(new Matrix4f(), rot.mul(1 / sin), sin, (float)Math.sqrt(1 - (sin * sin)));
		ret = incl.mul(ret);
	    }
	    return(ret);
	}
    }

    public static class BasePlace extends DefaultPlace {
	public final Coord2d[][] obst;
	private Coord2d cc;
	private double ca;
	private int seq = -1;
	private float z;

	public BasePlace(MCache map, MCache.SurfaceID surf, Coord2d[][] obst) {
	    super(map, surf);
	    this.obst = obst;
	}

	public BasePlace(MCache map, MCache.SurfaceID surf, Resource res, String id) {
	    this(map, surf, res.flayer(Resource.obst, id).p);
	}

	public BasePlace(MCache map, MCache.SurfaceID surf, Resource res) {
	    this(map, surf, res, "");
	}

	private float getz(Coord2d rc, double ra) {
	    Coord2d[][] no = this.obst, ro = new Coord2d[no.length][];
	    {
		double s = Math.sin(ra), c = Math.cos(ra);
		for(int i = 0; i < no.length; i++) {
		    ro[i] = new Coord2d[no[i].length];
		    for(int o = 0; o < ro[i].length; o++)
			ro[i][o] = Coord2d.of((no[i][o].x * c) - (no[i][o].y * s), (no[i][o].y * c) + (no[i][o].x * s)).add(rc);
		}
	    }
	    float ret = Float.NaN;
	    for(int i = 0; i < no.length; i++) {
		for(int o = 0; o < ro[i].length; o++) {
		    Coord2d a = ro[i][o], b = ro[i][(o + 1) % ro[i].length];
		    for(Coord2d c : new Coord2d.GridIsect(a, b, MCache.tilesz, false)) {
			double z = map.getz(surf, c);
			if(Float.isNaN(ret) || (z < ret))
			    ret = (float)z;
		    }
		}
	    }
	    return(ret);
	}

	public Coord3f getc(Coord2d rc, double ra) {
	    int mseq = map.chseq;
	    if((mseq != this.seq) || !Utils.eq(rc, cc) || (ra != ca)) {
		this.z = getz(rc, ra);
		this.seq = mseq;
		this.cc = rc;
		this.ca = ra;
	    }
	    return(Coord3f.of((float)rc.x, (float)rc.y, this.z));
	}
    }

    public static class PlanePlace extends DefaultPlace {
	public final Coord2d[] points;
	private Coord3f c;
	private Matrix4f r = Matrix4f.id;
	private int seq = -1;
	private Coord2d cc;
	private double ca;

	public static Coord2d[] flatten(Coord2d[][] points) {
	    int n = 0;
	    for(int i = 0; i < points.length; i++)
		n += points[i].length;
	    Coord2d[] ret = new Coord2d[n];
	    for(int i = 0, o = 0; i < points.length; o += points[i++].length)
		System.arraycopy(points[i], 0, ret, o, points[i].length);
	    return(ret);
	}

	public PlanePlace(MCache map, MCache.SurfaceID surf, Coord2d[] points) {
	    super(map, surf);
	    this.points = points;
	}

	public PlanePlace(MCache map, MCache.SurfaceID surf, Coord2d[][] points) {
	    this(map, surf, flatten(points));
	}

	public PlanePlace(MCache map, MCache.SurfaceID surf, Resource res, String id) {
	    this(map, surf, res.flayer(Resource.obst, id).p);
	}

	public PlanePlace(MCache map, MCache.SurfaceID surf, Resource res) {
	    this(map, surf, res, "");
	}

	private void recalc(Coord2d rc, double ra) {
	    double s = Math.sin(ra), c = Math.cos(ra);
	    Coord3f[] pp = new Coord3f[points.length];
	    for(int i = 0; i < pp.length; i++) {
		Coord2d rv = Coord2d.of((points[i].x * c) - (points[i].y * s), (points[i].y * c) + (points[i].x * s));
		pp[i] = map.getzp(surf, rv.add(rc));
	    }
	    int I = 0, O = 1, U = 2;
	    Coord3f mn = Coord3f.zu;
	    double ma = 0;
	    for(int i = 0; i < pp.length - 2; i++) {
		for(int o = i + 1; o < pp.length - 1; o++) {
		    plane: for(int u = o + 1; u < pp.length; u++) {
			Coord3f n = pp[o].sub(pp[i]).cmul(pp[u].sub(pp[i])).norm();
			for(int p = 0; p < pp.length; p++) {
			    if((p == i) || (p == o) || (p == u))
				continue;
			    float pz = (((n.x * (pp[i].x - pp[p].x)) + (n.y * (pp[i].y - pp[p].y))) / n.z) + pp[i].z;
			    if(pz < pp[p].z - 0.01)
				continue plane;
			}
			double a = n.cmul(Coord3f.zu).abs();
			if(a > ma) {
			    mn = n;
			    ma = a;
			    I = i; O = o; U = u;
			}
		    }
		}
	    }
	    this.c = Coord3f.of((float)rc.x, (float)rc.y, (((mn.x * (pp[I].x - (float)rc.x)) + (mn.y * (pp[I].y - (float)rc.y))) / mn.z) + pp[I].z);
	    this.r = Transform.makerot(new Matrix4f(), Coord3f.zu, -(float)ra);
	    mn.y = -mn.y;
	    Coord3f rot = Coord3f.zu.cmul(mn);
	    float sin = rot.abs();
	    if(sin > 0) {
		Matrix4f incl = Transform.makerot(new Matrix4f(), rot.mul(1 / sin), sin, (float)Math.sqrt(1 - (sin * sin)));
		this.r = incl.mul(this.r);
	    }
	}

	private void check(Coord2d rc, double ra) {
	    int mseq = map.chseq;
	    if((mseq != this.seq) || !Utils.eq(rc, cc) || (ra != ca)) {
		recalc(rc, ra);
		this.seq = mseq;
		this.cc = rc;
		this.ca = ra;
	    }
	}

	public Coord3f getc(Coord2d rc, double ra) {
	    check(rc, ra);
	    return(this.c);
	}

	public Matrix4f getr(Coord2d rc, double ra) {
	    return(this.r);
	}
    }

    public Gob(Glob glob, Coord2d c, long id) {
	this.glob = glob;
	this.rc = c;
	this.id = id;
	if(id < 0)
	    virtual = true;
	if(GobDamageInfo.has(this)) {
	    addDmg();
	}
	setupmods.add(customColor);
	info = new GeneralGobInfo(this);
	setattr(info);
	updwait(this::drawableUpdated, waiting -> {});
    }

    public Gob(Glob glob, Coord2d c) {
	this(glob, c, -1);
    }
    
    private Map<Class<? extends GAttrib>, GAttrib> cloneattrs() {
	synchronized (this.attr) {
	    return new HashMap<>(this.attr);
	}
    }
    
    public void ctick(double dt) {
	Map<Class<? extends GAttrib>, GAttrib> attr = cloneattrs();
	for(GAttrib a : attr.values())
	    a.ctick(dt);
	for(Iterator<Overlay> i = ols.iterator(); i.hasNext();) {
	    Overlay ol = i.next();
	    if(ol.slots == null) {
		try {
		    ol.init();
		} catch(Loading e) {}
	    } else {
		boolean done = ol.spr.tick(dt);
		if((!ol.delign || (ol.spr instanceof Sprite.CDel)) && done) {
		    ol.remove0();
		    i.remove();
		}
	    }
	}
	updstate();
	if(virtual && ols.isEmpty() && (getattr(Drawable.class) == null))
	    glob.oc.remove(this);
    
	if(isMe == null) {
	    isMe();
	    if(isMe != null) {
		tagsUpdated();
	    }
	}
	long tseq = eseq();
	if(eseq != tseq && is(GobTag.ANIMAL)) {
	    eseq = tseq;
	    tagsUpdated();
	}
	if(!mapProcessed && context(MapWnd2.class) != null) {
	    mapProcessed = true;
	    status.update(StatusType.marker);
	}
	updateState();
    }

    public void gtick(Render g) {
	Drawable d = getattr(Drawable.class);
	if(d != null)
	    d.gtick(g);
	for(Overlay ol : ols) {
	    if(ol.spr != null)
		ol.spr.gtick(g);
	}
    }

    void removed() {
	removed = true;
    }

    private void deferred() {
	while(true) {
	    Runnable task;
	    synchronized(deferred) {
		task = deferred.peek();
		if(task == null) {
		    deferral = null;
		    return;
		}
	    }
	    synchronized(this) {
		if(!removed)
		    task.run();
	    }
	    if(task instanceof Disposable)
		((Disposable)task).dispose();
	    synchronized(deferred) {
		if(deferred.poll() != task)
		    throw(new RuntimeException());
	    }
	}
    }

    public void defer(Runnable task) {
	synchronized(deferred) {
	    deferred.add(task);
	    if(deferral == null)
		deferral = glob.loader.defer(this::deferred, null);
	}
    }

    public void addol(Overlay ol, boolean async) {
	if(async) {
	    defer(() -> addol(ol, false));
	    return;
	}
	ol.init();
	ol.add0();
	ols.add(ol);
	overlayAdded(ol);
	overlaysUpdated();
    }
    public void addol(Overlay ol) {
	addol(ol, true);
    }
    public void addol(Sprite ol) {
	addol(new Overlay(this, ol));
    }
    public void addol(Indir<Resource> res, Message sdt) {
	addol(new Overlay(this, -1, res, sdt));
    }

    public Overlay findol(int id) {
	for(Overlay ol : ols) {
	    if(ol.id == id)
		return(ol);
	}
	return(null);
    }

    private void overlayAdded(Overlay item) {
	try {
	    Indir<Resource> indir = item.res;
	    if(indir != null) {
		Resource res = indir.get();
		if(res != null) {
		    if(res.name.equals("gfx/fx/floatimg")) {
			processDmg(item.sdt.clone());
		    } else if(res.name.equals("gfx/fx/dowse")) {
		        ProspectingWnd.overlay(this, item);
		    }
//		    System.out.printf("overlayAdded: '%s'%n", res.name);
		}
	    }
	} catch (Loading ignored) {}
    }
    
    private void processDmg(MessageBuf msg) {
	try {
	    msg.rewind();
	    int v = msg.int32();
	    msg.uint8();
	    int c = msg.uint16();
//	    System.out.println(String.format("processDmg v: %d, c: %d", v, c));
	    
	    if(damage == null) {
		addDmg();
	    }
	    damage.update(c, v);
	} catch (Exception ignored) {
	    ignored.printStackTrace();
	}
    }
    
    private void addDmg() {
	damage = new GobDamageInfo(this);
	setattr(GobDamageInfo.class, damage);
    }
    
    public void clearDmg() {
	setattr(GobDamageInfo.class, null);
	damage = null;
    }
    
    public void rclick() {
	try {
	    MapView map = glob.sess.ui.gui.map;
	    map.click(this, 3, Coord.z);
	} catch (Exception ignored) {}
    }
    
    public void itemact() {
	try {
	    UI ui = glob.sess.ui;
	    Coord mc = rc.floor(posres);
	    MapView map = ui.gui.map;
	    map.wdgmsg("itemact", ui.mc, mc, ui.modflags(), 0, (int)id, mc, 0, -1);
	} catch (Exception ignored) {}
    }
    
    public void tick() {
	Map<Class<? extends GAttrib>, GAttrib> attr = cloneattrs();
	for (GAttrib a : attr.values())
	    a.tick();
    }
    
    public void waitRemoval() throws InterruptedException {
	synchronized (removalLock) {
	    removalLock.wait(15000);
	    if(!disposed) {
		throw new InterruptedException();
	    }
	}
    }
    
    public boolean disposed() {return disposed;}
    
    public void dispose() {
	drawable = null;
	moving = null;
	synchronized (removalLock) {
	    disposed = true;
	    removalLock.notifyAll();
	}
	Map<Class<? extends GAttrib>, GAttrib> attr = cloneattrs();
	for(GAttrib a : attr.values()) {
	    if(a instanceof Moving) {updateMovingInfo(null, a);}
	    a.dispose();
	}
    }

    public void move(Coord2d c, double a) {
	Moving m = getattr(Moving.class);
	if(m != null)
	    m.move(c);
	
	try {
	    if(Boolean.TRUE.equals(isMe()) && CFG.AUTOMAP_TRACK.get()) {
		MappingClient.getInstance().CheckGridCoord(c);
		MappingClient.getInstance().Track(id, c);
	    }
	} catch (Exception ex) {}
	
	this.rc = c;
	this.a = a;
    }
    
    public Boolean isMe() {
	if(isMe == null) {
	    if(glob.sess.ui.gui == null || glob.sess.ui.gui.map == null || glob.sess.ui.gui.map.plgob < 0) {
		return null;
	    } else {
		isMe = id == glob.sess.ui.gui.map.plgob;
	    }
	}
	return isMe;
    }

    public Placer placer() {
	Drawable d = getattr(Drawable.class);
	if(d != null) {
	    Placer ret = d.placer();
	    if(ret != null)
		return(ret);
	}
	return(glob.map.mapplace);
    }

    public Coord3f getc() {
	Moving m = getattr(Moving.class);
	Coord3f ret = (m != null) ? m.getc() : getrc();
	DrawOffset df = getattr(DrawOffset.class);
	if(df != null)
	    ret = ret.add(df.off);
	return(ret);
    }

    public Coord3f getrc() {
	return(placer().getc(rc, a));
    }

    protected Pipe.Op getmapstate(Coord3f pc) {
	Tiler tile = glob.map.tiler(glob.map.gettile(new Coord2d(pc).floor(MCache.tilesz)));
	return(tile.drawstate(glob, pc));
    }

    private Class<? extends GAttrib> attrclass(Class<? extends GAttrib> cl) {
	while(true) {
	    Class<?> p = cl.getSuperclass();
	    if(p == GAttrib.class)
		return(cl);
	    cl = p.asSubclass(GAttrib.class);
	}
    }

    public <C extends GAttrib> C getattr(Class<C> c) {
	synchronized (attr) {
	    GAttrib attr = this.attr.get(attrclass(c));
	    if(!c.isInstance(attr))
		return (null);
	    return (c.cast(attr));
	}
    }

    private void setattr(Class<? extends GAttrib> ac, GAttrib a) {
	GAttrib prev;
	synchronized (attr) {
	    prev = attr.remove(ac);
	    if(prev != null) {
		if((prev instanceof RenderTree.Node) && (prev.slots != null))
		    RUtils.multirem(new ArrayList<>(prev.slots));
		if(prev instanceof SetupMod)
		    setupmods.remove(prev);
	    }
	    if(a != null) {
		if(a instanceof RenderTree.Node && !a.skipRender) {
		    try {
			RUtils.multiadd(this.slots, (RenderTree.Node) a);
		    } catch (Loading l) {
			if(prev instanceof RenderTree.Node && !prev.skipRender) {
			    RUtils.multiadd(this.slots, (RenderTree.Node) prev);
			    attr.put(ac, prev);
			}
			if(prev instanceof SetupMod)
			    setupmods.add((SetupMod) prev);
			throw (l);
		    }
		}
		if(a instanceof SetupMod)
		    setupmods.add((SetupMod) a);
		attr.put(ac, a);
	    }
	    if(prev != null)
		prev.dispose();
	    if(ac == Drawable.class) {
		drawable = (Drawable) a;
		if(a != prev) drawableUpdated();
	    } else if(ac == Moving.class) {
		moving = (Moving) a;
	    } else if(ac == KinInfo.class) {
		tagsUpdated();
	    } else if(ac == GobHealth.class) {
		status.update(StatusType.info);
	    }
	}
	if(ac == Moving.class) {updateMovingInfo(a, prev);}
    }

    public void setattr(GAttrib a) {
	setattr(attrclass(a.getClass()), a);
    }

    public void delattr(Class<? extends GAttrib> c) {
	setattr(attrclass(c), null);
    }

    public Supplier<? extends Pipe.Op> eqpoint(String nm, Message dat) {
	for(GAttrib attr : this.attr.values()) {
	    if(attr instanceof EquipTarget) {
		Supplier<? extends Pipe.Op> ret = ((EquipTarget)attr).eqpoint(nm, dat);
		if(ret != null)
		    return(ret);
	    }
	}
	return(null);
    }

    public static class GobClick extends Clickable {
	public final Gob gob;

	public GobClick(Gob gob) {
	    this.gob = gob;
	}

	public Object[] clickargs(ClickData cd) {
	    Object[] ret = {0, (int)gob.id, gob.rc.floor(OCache.posres), 0, -1};
	    for(Object node : cd.array()) {
		if(node instanceof Gob.Overlay) {
		    ret[0] = 1;
		    ret[3] = ((Gob.Overlay)node).id;
		}
		if(node instanceof FastMesh.ResourceMesh)
		    ret[4] = ((FastMesh.ResourceMesh)node).id;
	    }
	    return(ret);
	}

	public String toString() {
	    return(String.format("#<gob-click %d %s>", gob.id, gob.getres()));
	}
    }

    protected void obstate(Pipe buf) {
    }

    private class GobState implements Pipe.Op {
	final Pipe.Op mods;

	private GobState() {
	    if(setupmods.isEmpty()) {
		this.mods = null;
	    } else {
		Pipe.Op[] mods = new Pipe.Op[setupmods.size()];
		int n = 0;
		for(SetupMod mod : setupmods) {
		    if((mods[n] = mod.gobstate()) != null)
			n++;
		}
		this.mods = (n > 0) ? Pipe.Op.compose(mods) : null;
	    }
	}

	public void apply(Pipe buf) {
	    if(!virtual)
		buf.prep(new GobClick(Gob.this));
	    buf.prep(new TickList.Monitor(Gob.this));
	    obstate(buf);
	    if(mods != null)
		buf.prep(mods);
	}

	public boolean equals(GobState that) {
	    return(Utils.eq(this.mods, that.mods));
	}
	public boolean equals(Object o) {
	    return((o instanceof GobState) && equals((GobState)o));
	}
    }
    private GobState curstate = null;
    private GobState curstate() {
	if(curstate == null)
	    curstate = new GobState();
	return(curstate);
    }

    private void updstate() {
	GobState nst;
	try {
	    nst = new GobState();
	} catch(Loading l) {
	    return;
	}
	if(!Utils.eq(nst, curstate)) {
	    for(RenderTree.Slot slot : slots)
		slot.ostate(nst);
	    this.curstate = nst;
	}
    }

    public void added(RenderTree.Slot slot) {
	slot.ostate(curstate());
	for(Overlay ol : ols) {
	    if(ol.slots != null)
		slot.add(ol);
	}
	Map<Class<? extends GAttrib>, GAttrib> attr = cloneattrs();
	for(GAttrib a : attr.values()) {
	    if(a instanceof RenderTree.Node && !a.skipRender)
		slot.add((RenderTree.Node) a);
	}
	slots.add(slot);
    }

    public void removed(RenderTree.Slot slot) {
	slots.remove(slot);
    }

    private Waitable.Queue updwait = null;
    void updated() {
	synchronized(this) {
	    updateseq++;
	    if(updwait != null)
		updwait.wnotify();
	}
    }

    public void updwait(Runnable callback, Consumer<Waitable.Waiting> reg) {
	/* Caller should probably synchronize on this already for a
	 * call like this to even be meaningful, but just in case. */
	synchronized(this) {
	    if(updwait == null)
		updwait = new Waitable.Queue();
	    reg.accept(updwait.add(callback));
	}
    }

    public static class DataLoading extends Loading {
	public final transient Gob gob;
	public final int updseq;

	/* It would be assumed that the caller has synchronized on gob
	 * while creating this exception. */
	public DataLoading(Gob gob, String message) {
	    super(message);
	    this.gob = gob;
	    this.updseq = gob.updateseq;
	}

	public void waitfor(Runnable callback, Consumer<Waitable.Waiting> reg) {
	    synchronized(gob) {
		if(gob.updateseq != this.updseq) {
		    reg.accept(Waitable.Waiting.dummy);
		    callback.run();
		} else {
		    gob.updwait(callback, reg);
		}
	    }
	}
    }

    public Random mkrandoom() {
	return(Utils.mkrandoom(id));
    }

    public Resource getres() {
	Drawable d = drawable;
	if(d != null)
	    return(d.getres());
	return(null);
    }

    public Skeleton.Pose getpose() {
	Drawable d = drawable;
	if(d != null)
	    return(d.getpose());
	return(null);
    }
    
    public String resid() {
	Drawable d = drawable;
	if(d != null)
	    return d.resId();
	return null;
    }
    
    private static final ClassResolver<Gob> ctxr = new ClassResolver<Gob>()
	.add(Gob.class, g -> g)
	.add(Glob.class, g -> g.glob)
	.add(GameUI.class, g -> (g.glob.sess.ui != null) ? g.glob.sess.ui.gui : null)
	.add(MapWnd2.class, g -> (g.glob.sess.ui != null && g.glob.sess.ui.gui != null) ? g.glob.sess.ui.gui.mapfile : null)
	.add(Session.class, g -> g.glob.sess);
    public <T> T context(Class<T> cl) {return(ctxr.context(cl, this));}

    @Deprecated
    public Glob glob() {return(context(Glob.class));}

    /* Because generic functions are too nice a thing for Java. */
    public double getv() {
	Moving m = getattr(Moving.class);
	if(m == null)
	    return(0);
	return(m.getv());
    }

    public Collection<Location.Chain> getloc() {
	Collection<Location.Chain> ret = new ArrayList<>(slots.size());
	for(RenderTree.Slot slot : slots)
	    ret.add(slot.state().get(Homo3D.loc));
	return(ret);
    }

    public class Placed implements RenderTree.Node, TickList.Ticking, TickList.TickNode {
	private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
	private Placement cur;

	private Placed() {}

	private class Placement implements Pipe.Op {
	    final Pipe.Op flw, tilestate, mods;
	    final Coord3f oc, rc;
	    final Matrix4f rot;

	    Placement() {
		try {
		    Following flw = Gob.this.getattr(Following.class);
		    Pipe.Op flwxf = (flw == null) ? null : flw.xf();
		    Pipe.Op tilestate = null;
		    if(flwxf == null) {
			Coord3f oc = Gob.this.getc();
			Coord3f rc = new Coord3f(oc);
			rc.y = -rc.y;
			this.flw = null;
			this.oc = oc;
			this.rc = rc;
			this.rot = Gob.this.placer().getr(Coord2d.of(oc), Gob.this.a);
			tilestate = Gob.this.getmapstate(oc);
		    } else {
			this.flw = flwxf;
			this.oc = this.rc = null;
			this.rot = null;
		    }
		    this.tilestate = tilestate;
		    if(setupmods.isEmpty()) {
			this.mods = null;
		    } else {
			Pipe.Op[] mods = new Pipe.Op[setupmods.size()];
			int n = 0;
			for(SetupMod mod : setupmods) {
			    if((mods[n] = mod.placestate()) != null)
				n++;
			}
			this.mods = (n > 0) ? Pipe.Op.compose(mods) : null;
		    }
		} catch(Loading bl) {
		    throw(new Loading(bl) {
			    public String getMessage() {return(bl.getMessage());}

			    public void waitfor(Runnable callback, Consumer<Waitable.Waiting> reg) {
				Waitable.or(callback, reg, bl, Gob.this::updwait);
			    }
			});
		}
	    }

	    public boolean equals(Placement that) {
		if(this.flw != null) {
		    if(!Utils.eq(this.flw, that.flw))
			return(false);
		} else {
		    if(!(Utils.eq(this.oc, that.oc) && Utils.eq(this.rot, that.rot)))
			return(false);
		}
		if(!Utils.eq(this.tilestate, that.tilestate))
		    return(false);
		if(!Utils.eq(this.mods, that.mods))
		    return(false);
		return(true);
	    }

	    public boolean equals(Object o) {
		return((o instanceof Placement) && equals((Placement)o));
	    }

	    Pipe.Op gndst = null;
	    public void apply(Pipe buf) {
		if(this.flw != null) {
		    this.flw.apply(buf);
		} else {
		    if(gndst == null)
			gndst = Pipe.Op.compose(new Location(Transform.makexlate(new Matrix4f(), this.rc), "gobx"),
						new Location(rot, "gob"));
		    gndst.apply(buf);
		}
		if(tilestate != null)
		    tilestate.apply(buf);
		if(mods != null)
		    mods.apply(buf);
	    }
	}

	public Pipe.Op placement() {
	    return(new Placement());
	}

	public void autotick(double dt) {
	    synchronized(Gob.this) {
		Placement np;
		try {
		    np = new Placement();
		} catch(Loading l) {
		    return;
		}
		if(!Utils.eq(this.cur, np))
		    update(np);
	    }
	}

	private void update(Placement np) {
	    for(RenderTree.Slot slot : slots)
		slot.ostate(np);
	    this.cur = np;
	}

	public void added(RenderTree.Slot slot) {
	    slot.ostate(curplace());
	    slot.add(Gob.this);
	    slots.add(slot);
	}

	public void removed(RenderTree.Slot slot) {
	    slots.remove(slot);
	}

	public Pipe.Op curplace() {
	    if(cur == null)
		cur = new Placement();
	    return(cur);
	}

	public Coord3f getc() {
	    return((this.cur != null) ? this.cur.oc : null);
	}

	public TickList.Ticking ticker() {return(this);}
    }
    
    public void highlight() {
	GobHighlight h = getattr(GobHighlight.class);
	if(h == null) {
	    h = new GobHighlight(this);
	    setattr(h);
	}
	h.start();
    }
    
    public String tooltip() {
	String tt = null;
	GobIcon icon = getattr(GobIcon.class);
	if(icon != null) {
	    tt = icon.tooltip();
	}
	if(tt == null) {
	    tt = Utils.prettyResName(resid());
	}
	return tt;
    }
    
    private void updateHitbox() {
	if(updateseq == 0) {return;}
	boolean hitboxEnabled = CFG.DISPLAY_GOB_HITBOX.get() || is(GobTag.HIDDEN);
	if(hitboxEnabled) {
	    if(hitbox != null) {
		if(!hitbox.show(true)) {
		    hitbox.fx.updateState();
		}
	    } else if(!virtual || this instanceof MapView.Plob) {
		Hitbox hitbox = Hitbox.forGob(this);
		if(hitbox != null) {
		    this.hitbox = new HidingGobSprite<>(this, hitbox);
		    addol(this.hitbox);
		}
	    }
	} else if(hitbox != null) {
	    hitbox.show(false);
	}
    }
    
    private boolean updateVisibility() {
	if(anyOf(GobTag.TREE, GobTag.BUSH)) {
	    Drawable d = drawable;
	    Boolean needHide = CFG.HIDE_TREES.get();
	    if(d != null && d.skipRender != needHide) {
		d.skipRender = needHide;
		if(needHide) {
		    if(d.slots != null) {
			ArrayList<RenderTree.Slot> tmpSlots = new ArrayList<>(d.slots);
			glob.loader.defer(() -> RUtils.multiremSafe(tmpSlots), null);
		    }
		} else {
		    ArrayList<RenderTree.Slot> tmpSlots = new ArrayList<>(slots);
		    glob.loader.defer(() -> RUtils.multiadd(tmpSlots, d), null);
		}
	    }
	    if(needHide) {
		tag(GobTag.HIDDEN);
	    } else {
	        untag(GobTag.HIDDEN);
	    }
	    return true;
	}
	return false;
    }
    
    private void updateIcon() {
	if(getattr(GobIcon.class) == null) {
	    GobIcon icon = Radar.getIcon(this);
	    if(icon != null) {
		setattr(icon);
	    }
	}
    }
    
    private void markGob() {
	if(isFake()) {return;}
	final MapWnd2 mapwnd = context(MapWnd2.class);
	if(mapwnd == null) {return;}
	AutoMarkers.marker(resid()).ifPresent(m -> mapwnd.markobj(m, rc));
    }
    
    public boolean isFake() {
	return this instanceof MapView.Plob || id < 0;
    }
    
    public final Placed placed = new Placed();
    
    private void updateTags() {
	Set<GobTag> tags = GobTag.tags(this);
	synchronized (this.tags) {
	    this.tags.clear();
	    this.tags.addAll(tags);
	}
    }
    
    public void tag(GobTag tag) {
	synchronized (this.tags) { this.tags.add(tag); }
    }
    
    public void untag(GobTag tag) {
	synchronized (this.tags) { this.tags.remove(tag); }
    }
    
    private void updateWarnings() {
	if(!GobWarning.needsWarning(this)) {
	    warning = null;
	    delattr(GobWarning.class);
	} else if(warning == null) {
	    warning = new GobWarning(this);
	    setattr(warning);
	}
    }
    
    public boolean is(GobTag tag) {
	synchronized (tags) {
	    return tags.contains(tag);
	}
    }
    
    public boolean anyOf(GobTag... tags) {
	synchronized (this.tags) {
	    for (GobTag tag : tags) {
		if(is(tag)) {return true;}
	    }
	}
	return false;
    }
    
    public static Gob from(Clickable ci) {
	if(ci instanceof Gob.GobClick) {
	    return ((GobClick) ci).gob;
	} else if(ci instanceof Composited.CompositeClick) {
	    GobClick gi = ((Composited.CompositeClick) ci).gi;
	    return gi != null ? gi.gob : null;
	}
	return null;
    }
    
    //Useful for getting stage information or model type
    public int sdt() {
        Drawable d = drawable;
        if(d instanceof ResDrawable) {
	    ResDrawable dw = (ResDrawable) d;
		return dw.sdtnum();
	}
	return 0;
    }
    
    public void setQuality(int q) {
        info.setQ(q);
        status.update(StatusType.info);
    }
    
    public void poseUpdated() {status.update(StatusType.pose);}
    
    public void idUpdated() {status.update(StatusType.id);}
    
    public void drawableUpdated() { status.update(StatusType.drawable); }
    
    public void overlaysUpdated() { status.update(StatusType.overlay); }
    
    public void kinUpdated() {status.update(StatusType.kin);}
    
    public void hitboxUpdated() {status.update(StatusType.hitbox);}
    
    public void visibilityUpdated() {status.update(StatusType.visibility);}
    
    public void infoUpdated() {status.update(StatusType.info);}
    
    public void iconUpdated() { status.update(StatusType.icon);}
    
    public void tagsUpdated() {status.update(StatusType.tags);}
    
    private void updateState() {
	if(updateseq == 0 || !status.updated()) {return;}
	StatusUpdates status = this.status;
	this.status = new StatusUpdates();
    
	if(status.updated(StatusType.drawable, StatusType.kin, StatusType.id, StatusType.pose, StatusType.tags, StatusType.overlay)) {
	    updateTags();
	    status.update(StatusType.tags);
	}
    
	if(status.updated(StatusType.drawable, StatusType.visibility, StatusType.tags)) {
	    if(updateVisibility()) {
		status.update(StatusType.visibility);
	    }
	}
    
	if(status.updated(StatusType.drawable) && radius == null) {
	    Resource res = getres();
	    if(res != null) {
		radius = GobRadius.get(res.name);
		if(radius != null) {
		    addol(new MSRad(this, radius.radius, radius.color(), radius.color2()));
		}
	    }
	}
    
	if(status.updated(StatusType.drawable, StatusType.hitbox, StatusType.visibility)) {
	    updateHitbox();
	}
    
	if(status.updated(StatusType.drawable, StatusType.id, StatusType.icon)) {
	    updateIcon();
	}
    
	if(status.updated(StatusType.tags)) {
	    updateWarnings();
	}
	
	if(status.updated(StatusType.info, StatusType.tags)) {
	    info.clean();
	}
    
	if(status.updated(StatusType.tags, StatusType.info)) {
	    updateColor();
	}
	
	if(status.updated(StatusType.marker, StatusType.id)) {
	    markGob();
	}
    }
    
    private void updateColor() {
	Color c = null;
	if(CFG.DISPLAY_GOB_INFO.get()) {
	    if(is(GobTag.DRACK)) {
		if(is(GobTag.EMPTY)) {
		    c = COL_EMPTY;
		} else if(is(GobTag.READY)) {
		    c = COL_READY;
		}
	    }
	    if(CFG.SHOW_CONTAINER_FULLNESS.get() && is(GobTag.CONTAINER)) {
		if(is(GobTag.EMPTY)) {
		    c = COL_EMPTY;
		} else if(is(GobTag.FULL)) {
		    c = COL_FULL;
		}
	    }
	}
	if(customColor.color(c)) {updstate();}
    }
    
    private static class StatusUpdates {
	private final Set<StatusType> updated = new HashSet<>();
	
	private void update(StatusType type) {
	    synchronized (updated) {
		updated.add(type);
	    }
	}
	
	private boolean updated(StatusType... types) {
	    synchronized (updated) {
		for (StatusType type : types) {
		    if(updated.contains(type)) {return true;}
		}
	    }
	    return false;
	}
	
	private boolean updated() {
	    return !updated.isEmpty();
	}
    }
    
    private enum StatusType {
	drawable, overlay, tags, pose, id, info, kin, hitbox, icon, visibility, marker
    }
    
    private void updateMovingInfo(GAttrib a, GAttrib prev) {
	boolean me = is(GobTag.ME);
	if(prev instanceof Moving) {
	    glob.oc.paths.removePath((Moving) prev);
	}
	if(a instanceof LinMove || a instanceof Homing) {
	    glob.oc.paths.addPath((Moving) a);
	}
	drives = 0;
	if(prev instanceof Following) {
	    Following follow = (Following) prev;
	    if(me) {
		Gob tgt = follow.tgt();
		if(tgt != null) {tgt.drivenByPlayer = false;}
	    }
	} else if(prev instanceof Homing) {
	    Homing homing = (Homing) prev;
	    if(me) {
		Gob tgt = homing.tgt();
		if(tgt != null) {tgt.drivenByPlayer = false;}
	    }
	}
	if(a instanceof Following) {
	    Following follow = (Following) a;
	    drives = follow.tgt;
	    if(me) {follow.tgt().drivenByPlayer = true;}
	} else if(a instanceof Homing) {
	    Homing homing = (Homing) a;
	    Gob tgt = homing.tgt();
	    if(tgt != null) {
		if(tgt.is(GobTag.PUSHED)) {
		    drives = homing.tgt;
		    if(me) { tgt.drivenByPlayer = true;}
		}
	    }
	}
	glob.sess.ui.pathQueue().ifPresent(pathQueue -> pathQueue.movementChange(this, prev, a));
    }
    
    private long eseq() {
	if(glob.sess.ui.gui != null && glob.sess.ui.gui.equipory != null) {
	    return glob.sess.ui.gui.equipory.seq;
	}
	return 0;
    }
}
