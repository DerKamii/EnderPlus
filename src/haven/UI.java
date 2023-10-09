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

import haven.rx.Reactor;
import me.ender.WindowDetector;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GraphicsDevice;
import java.awt.DisplayMode;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import static haven.Utils.el;
import haven.render.Environment;
import haven.render.Render;

public class UI {
    public static int MOD_SHIFT = KeyMatch.S, MOD_CTRL = KeyMatch.C, MOD_META = KeyMatch.M, MOD_SUPER = KeyMatch.SUPER;
    enum KeyMod {
        SHIFT(MOD_SHIFT), CTRL(MOD_CTRL), ALT(MOD_META);
    
	public final int mod;
    
	KeyMod(int mod) {
	    this.mod = mod;
	}
    }
    public RootWidget root;
    private final LinkedList<Grab> keygrab = new LinkedList<Grab>(), mousegrab = new LinkedList<Grab>();
    private final Map<Integer, Widget> widgets = new TreeMap<Integer, Widget>();
    private final Map<Widget, Integer> rwidgets = new HashMap<Widget, Integer>();
    Environment env;
    Receiver rcvr;
    public Coord mc = Coord.z, lcc = Coord.z;
    public Session sess;
    public boolean modshift, modctrl, modmeta, modsuper;
    public Object lasttip;
    public double lastevent, lasttick;
    public Widget mouseon;
    public Console cons = new WidgetConsole();
    private Collection<AfterDraw> afterdraws = new LinkedList<AfterDraw>();
    private final Context uictx;
    public GSettings gprefs = GSettings.load(true);
    private boolean gprefsdirty = false;
    public final ActAudio.Root audio = new ActAudio.Root();
    private static final double scalef;
    public GameUI gui = null;
    
    {
	lastevent = lasttick = Utils.rtime();
    }
	
    public interface Receiver {
	public void rcvmsg(int widget, String msg, Object... args);
    }

    public interface Runner {
	public Runner run(UI ui) throws InterruptedException;
	public default void init(UI ui) {}
	public default String title() {return(null);}

	public static class Proxy implements Runner {
	    public final Runner back;

	    public Proxy(Runner back) {
		this.back = back;
	    }

	    public Runner run(UI ui) throws InterruptedException {return(back.run(ui));}
	    public void init(UI ui) {back.init(ui);}
	    public String title() {return(back.title());}
	}
    }

    public interface Context {
	void setmousepos(Coord c);
    }

    public interface AfterDraw {
	public void draw(GOut g);
    }

    public void setgprefs(GSettings prefs) {
	synchronized(this) {
	    if(!Utils.eq(prefs, this.gprefs)) {
		this.gprefs = prefs;
		gprefsdirty = true;
	    }
	}
    }

    private class WidgetConsole extends Console {
	{
	    setcmd("q", new Command() {
		    public void run(Console cons, String[] args) {
			HackThread.tg().interrupt();
		    }
		});
	    setcmd("lo", new Command() {
		    public void run(Console cons, String[] args) {
			sess.close();
		    }
		});
	    setcmd("gl", new Command() {
		    <T> void merd(GSettings.Setting<T> var, String val) {
			setgprefs(gprefs.update(null, var, var.parse(val)));
		    }

		    public void run(Console cons, String[] args) throws Exception {
			if(args.length < 3)
			    throw(new Exception("usage: gl SETTING VALUE"));
			GSettings.Setting<?> var = gprefs.find(args[1]);
			if(var == null)
			    throw(new Exception("No such setting: " + var));
			merd(var, args[2]);
		    }
		});
	}
	
	private void findcmds(Map<String, Command> map, Widget wdg) {
	    if(wdg instanceof Directory) {
		Map<String, Command> cmds = ((Directory)wdg).findcmds();
		synchronized(cmds) {
		    map.putAll(cmds);
		}
	    }
	    for(Widget ch = wdg.child; ch != null; ch = ch.next)
		findcmds(map, ch);
	}

	public Map<String, Command> findcmds() {
	    Map<String, Command> ret = super.findcmds();
	    findcmds(ret, root);
	    return(ret);
	}
    }

    public static class UIException extends RuntimeException {
	public String mname;
	public Object[] args;

	public UIException(String message, String mname, Object... args) {
	    super(message);
	    this.mname = mname;
	    this.args = args;
	}
    }

    public static class UIWarning extends Warning {
	public String mname;
	public Object[] args;

	public UIWarning(String message, String mname, Object... args) {
	    super(message);
	    this.mname = mname;
	    this.args = args;
	}
    }

    public UI(Context uictx, Coord sz, Runner fun) {
	this.uictx = uictx;
	root = new RootWidget(this, sz);
	widgets.put(0, root);
	rwidgets.put(root, 0);
	if(fun != null)
	    fun.init(this);
    }

    public void setreceiver(Receiver rcvr) {
	this.rcvr = rcvr;
    }
	
    public void bind(Widget w, int id) {
	synchronized(widgets) {
	    widgets.put(id, w);
	    rwidgets.put(w, id);
	    w.bound();
	}
    }

    public Widget getwidget(int id) {
	synchronized(widgets) {
	    return(widgets.get(id));
	}
    }

    public int widgetid(Widget wdg) {
	synchronized(widgets) {
	    Integer id = rwidgets.get(wdg);
	    if(id == null)
		return(-1);
	    return(id);
	}
    }

    public void drawafter(AfterDraw ad) {
	synchronized(afterdraws) {
	    afterdraws.add(ad);
	}
    }

    public void tick() {
	double now = Utils.rtime();
	double delta = now - lasttick;
	lasttick = now;
	root.tick(delta);
	if(gprefsdirty) {
	    gprefs.save();
	    gprefsdirty = false;
	}
    }

    public void gtick(Render out) {
	root.gtick(out);
    }

    public void draw(GOut g) {
	root.draw(g);
	synchronized(afterdraws) {
	    for(AfterDraw ad : afterdraws)
		ad.draw(g);
	    afterdraws.clear();
	}
    }
	
    public void newwidget(int id, String type, int parent, Object[] pargs, Object... cargs) throws InterruptedException {
	Widget.Factory f = Widget.gettype2(type);
	if(f == null)
	    throw(new UIException("Bad widget name", type, cargs));
	synchronized(this) {
	    Widget pwdg = parent != -1 ? getwidget(parent) : null;
	    Widget wdg = WindowDetector.create(pwdg, f, this, cargs);
	    wdg.attach(this);
	    if(parent != -1) {
		if(pwdg == null)
		    throw(new UIException("Null parent widget " + parent + " for " + id, type, cargs));
		pwdg.addchild(wdg, pargs);
	    }
	    bind(wdg, id);
	    if(wdg instanceof Window) {
		WindowDetector.detect((Window) wdg);
	    }
	}
    }

    public void addwidget(int id, int parent, Object[] pargs) {
	synchronized(this) {
	    Widget wdg = getwidget(id);
	    if(wdg == null)
		throw(new UIException("Null child widget " + id + " added to " + parent, null, pargs));
	    Widget pwdg = getwidget(parent);
	    if(pwdg == null)
		throw(new UIException("Null parent widget " + parent + " for " + id, null, pargs));
	    pwdg.addchild(wdg, pargs);
	}
    }

    public abstract class Grab {
	public final Widget wdg;
	public Grab(Widget wdg) {this.wdg = wdg;}
	public abstract void remove();
    }

    public Grab grabmouse(Widget wdg) {
	if(wdg == null) throw(new NullPointerException());
	Grab g = new Grab(wdg) {
		public void remove() {
		    mousegrab.remove(this);
		}
	    };
	mousegrab.addFirst(g);
	return(g);
    }

    public Grab grabkeys(Widget wdg) {
	if(wdg == null) throw(new NullPointerException());
	Grab g = new Grab(wdg) {
		public void remove() {
		    keygrab.remove(this);
		}
	    };
	keygrab.addFirst(g);
	return(g);
    }

    private void removeid(Widget wdg) {
	synchronized(widgets) {
	    Integer id = rwidgets.get(wdg);
	    if(id != null) {
		widgets.remove(id);
		rwidgets.remove(wdg);
	    }
	}
	for(Widget child = wdg.child; child != null; child = child.next)
	    removeid(child);
    }
	
    public void removed(Widget wdg) {
	for(Iterator<Grab> i = mousegrab.iterator(); i.hasNext();) {
	    Grab g = i.next();
	    if(g.wdg.hasparent(wdg))
		i.remove();
	}
	for(Iterator<Grab> i = keygrab.iterator(); i.hasNext();) {
	    Grab g = i.next();
	    if(g.wdg.hasparent(wdg))
		i.remove();
	}
    }

    public void destroy(Widget wdg) {
	removeid(wdg);
	wdg.reqdestroy();
    }
    
    public void destroy(int id) {
	synchronized(this) {
	    Widget wdg = getwidget(id);
	    if(wdg != null)
		destroy(wdg);
	}
    }
	
    public void wdgmsg(Widget sender, String msg, Object... args) {
	int id = widgetid(sender);
	if(id < 0) {
	    new Warning("wdgmsg sender (%s) is not in rwidgets, message is %s", sender.getClass().getName(), msg).issue();
	    return;
	}
	if(rcvr != null)
	    rcvr.rcvmsg(id, msg, args);
    }
	
    public void uimsg(int id, String msg, Object... args) {
	Widget wdg = getwidget(id);
	if(wdg != null) {
	    synchronized(this) {
		wdg.uimsg(msg.intern(), args);
	    }
	} else {
	    throw(new UIException("Uimsg to non-existent widget " + id, msg, args));
	}
    }
	
    public static interface MessageWidget {
	public void msg(String msg);
	public void error(String msg);

	public static MessageWidget find(Widget w) {
	    for(Widget ch = w.child; ch != null; ch = ch.next) {
		MessageWidget ret = find(ch);
		if(ret != null)
		    return(ret);
	    }
	    if(w instanceof MessageWidget)
		return((MessageWidget)w);
	    return(null);
	}
    }

    public void error(String msg) {
	MessageWidget h = MessageWidget.find(root);
	if(h != null)
	    h.error(msg);
    }

    public void msg(String msg) {
	Reactor.IMSG.onNext(msg);
	MessageWidget h = MessageWidget.find(root);
	if(h != null)
	    h.msg(msg);
    }

    private void setmods(InputEvent ev) {
	setmods(ev.getModifiersEx());
    }
    
    private void setmods(int mod) {
	modshift = (mod & InputEvent.SHIFT_DOWN_MASK) != 0;
	modctrl = (mod & InputEvent.CTRL_DOWN_MASK) != 0;
	modmeta = (mod & (InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) != 0;
	/*
	modsuper = (mod & InputEvent.SUPER_DOWN_MASK) != 0;
	*/
    }

    private Grab[] c(Collection<Grab> g) {return(g.toArray(new Grab[0]));}

    public void keydown(KeyEvent ev) {
	setmods(ev);
	for(Grab g : c(keygrab)) {
	    if(g.wdg.keydown(ev))
		return;
	}
	if(!root.keydown(ev)) {
	    char key = ev.getKeyChar();
	    if(key == ev.CHAR_UNDEFINED)
		key = 0;
	    root.globtype(key, ev);
	}
	root.processModDown(ev);
    }
	
    public void keyup(KeyEvent ev) {
	setmods(ev);
	for(Grab g : c(keygrab)) {
	    if(g.wdg.keyup(ev))
		return;
	}
	root.keyup(ev);
	root.processModUp(ev);
    }
	
    private Coord wdgxlate(Coord c, Widget wdg) {
	return(c.sub(wdg.rootpos()));
    }
	
    public boolean dropthing(Widget w, Coord c, Object thing) {
	if(w instanceof DropTarget) {
	    if(((DropTarget)w).dropthing(c, thing))
		return(true);
	}
	for(Widget wdg = w.lchild; wdg != null; wdg = wdg.prev) {
	    Coord cc = w.xlate(wdg.c, true);
	    if(c.isect(cc, wdg.sz)) {
		if(dropthing(wdg, c.add(cc.inv()), thing))
		    return(true);
	    }
	}
	return(false);
    }

    public void mousedown(Coord c, int button) {
        setmods(0);
	processMouseDown(c, button);
    }

    public void mousedown(MouseEvent ev, Coord c, int button) {
	setmods(ev);
	processMouseDown(c, button);
    }
    
    public void processMouseDown(Coord c, int button) {
	lcc = mc = c;
	for(Grab g : c(mousegrab)) {
	    if(g.wdg.mousedown(wdgxlate(c, g.wdg), button))
		return;
	}
	root.mousedown(c, button);
    }
    
    public void mouseup(Coord c, int button) {
        setmods(0);
	processMouseUp(c, button);
    }
	
    public void mouseup(MouseEvent ev, Coord c, int button) {
	setmods(ev);
	processMouseUp(c, button);
    }
    
    public void processMouseUp(Coord c, int button) {
	mc = c;
	for(Grab g : c(mousegrab)) {
	    if(g.wdg.mouseup(wdgxlate(c, g.wdg), button))
		return;
	}
	root.mouseup(c, button);
    }
    
    public void mousemove(Coord c) {
	processMouseMove(c);
    }
    
    public void mousemove(MouseEvent ev, Coord c) {
	setmods(ev);
	processMouseMove(c);
    }
    
    public void processMouseMove(Coord c) {
	mc = c;
	root.mousemove(c);
    }

    public void mousehover(Coord c) {
	root.mousehover(c);
    }

    public void setmousepos(Coord c) {
	uictx.setmousepos(c);
    }
	
    public void mousewheel(MouseEvent ev, Coord c, int amount) {
	setmods(ev);
	lcc = mc = c;
	for(Grab g : c(mousegrab)) {
	    if(g.wdg.mousewheel(wdgxlate(c, g.wdg), amount))
		return;
	}
	root.mousewheel(c, amount);
    }

    public void mouseclick(MouseEvent ev, Coord c, int button, int count) {
        setmods(ev);
        lcc = mc = c;
        for(Grab g : c(mousegrab)) {
            if(g.wdg.mouseclick(wdgxlate(c, g.wdg), button, count))
                return;
        }
        root.mouseclick(c, button, count);
    }
    

    public Resource getcurs(Coord c) {
	for(Grab g : mousegrab) {
	    Resource ret = g.wdg.getcurs(wdgxlate(c, g.wdg));
	    if(ret != null)
		return(ret);
	}
	return(root.getcurs(c));
    }
    
    public boolean isCursor(String name) {
	Resource curs = null;
	try {
	    curs = root.cursor != null ? root.cursor.get() : null;
	} catch (Loading ignored) {
	}
	return curs != null && curs.name.equals(name);
    }

    public static int modflags(InputEvent ev) {
	int mod = ev.getModifiersEx();
	return((((mod & InputEvent.SHIFT_DOWN_MASK) != 0) ? MOD_SHIFT : 0) |
	       (((mod & InputEvent.CTRL_DOWN_MASK) != 0)  ? MOD_CTRL : 0) |
	       (((mod & (InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) != 0) ? MOD_META : 0)
	       /* (((mod & InputEvent.SUPER_DOWN_MASK) != 0) ? MOD_SUPER : 0) */);
    }

    public int modflags() {
	return((modshift ? MOD_SHIFT : 0) |
	       (modctrl  ? MOD_CTRL  : 0) |
	       (modmeta  ? MOD_META  : 0) |
	       (modsuper ? MOD_SUPER : 0));
    }

    public void message(String str, GameUI.MsgType type) {
	if((cons!=null) && (gui!=null)){
	    gui.msg(str, type);
	}
    }

    public void message(String str, Color msgColor) {
	if((cons != null) && (gui != null)) {
	    gui.msg(str, msgColor);
	}
    }
    
    public void message(String str, Color msgColor, boolean sfx) {
	if((cons != null) && (gui != null)) {
	    gui.msg(str, msgColor, sfx);
	}
    }
    
    public void message(String str, Color msgColor, Resource sfx) {
	if((cons != null) && (gui != null)) {
	    gui.msg(str, msgColor, sfx);
	}
    }

    public Environment getenv() {
	return(env);
    }

    public void destroy() {
	root.destroy();
	audio.clear();
    }
    
    public Optional<PathQueue> pathQueue() {
	return (gui != null && gui.pathQueue != null) ? Optional.of(gui.pathQueue) : Optional.empty();
    }

    public void sfx(Audio.CS clip) {
	audio.aui.add(clip);
    }
    public void sfx(Resource clip) {
	sfx(Audio.fromres(clip));
    }

    public static double scale(double v) {
	return(v * scalef);
    }

    public static float scale(float v) {
	return(v * (float)scalef);
    }

    public static int scale(int v) {
	return(Math.round(scale((float)v)));
    }

    public static int rscale(double v) {
	return((int)Math.round(v * scalef));
    }

    public static Coord scale(Coord v) {
	return(v.mul(scalef));
    }

    public static Coord scale(int x, int y) {
	return(scale(new Coord(x, y)));
    }

    public static Coord rscale(double x, double y) {
	return(new Coord(rscale(x), rscale(y)));
    }

    public static Coord2d scale(Coord2d v) {
	return(v.mul(scalef));
    }

    static public Font scale(Font f, float size) {
	return(f.deriveFont(scale(size)));
    }

    public static <T extends Tex> ScaledTex<T> scale(T tex) {
	return(new ScaledTex<T>(tex, UI.scale(tex.sz())));
    }

    public static <T extends Tex> ScaledTex<T> scale(ScaledTex<T> tex) {
	return(tex);
    }

    public static double unscale(double v) {
	return(v / scalef);
    }

    public static float unscale(float v) {
	return(v / (float)scalef);
    }

    public static int unscale(int v) {
	return(Math.round(unscale((float)v)));
    }

    public static Coord unscale(Coord v) {
	return(v.div(scalef));
    }

    private static double maxscale = -1;
    public static double maxscale() {
	synchronized(UI.class) {
	    if(maxscale < 0) {
		double fscale = 1.25;
		try {
		    GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
		    for(GraphicsDevice dev : env.getScreenDevices()) {
			DisplayMode mode = dev.getDisplayMode();
			double scale = Math.min(mode.getWidth() / 800.0, mode.getHeight() / 600.0);
			fscale = Math.max(fscale, scale);
		    }
		} catch(Exception exc) {
		    new Warning(exc, "could not determine maximum scaling factor").issue();
		}
		maxscale = fscale;
	    }
	    return(maxscale);
	}
    }

    public static final Config.Variable<Double> uiscale = Config.Variable.propf("haven.uiscale", null);
    private static double loadscale() {
	if(uiscale.get() != null)
	    return(uiscale.get());
	double scale = Utils.getprefd("uiscale", 1.0);
	scale = Math.max(Math.min(scale, maxscale()), 1.0);
	return(scale);
    }
    
    public long plid() {
	if(gui != null) {
	    return gui.plid;
	}
	return 0;
    }
    
    public Optional<Equipory> equipory() {
	if(gui != null && gui.equipory != null) {
	    return Optional.of(gui.equipory);
	}
	return Optional.empty();
    }

    static {
	scalef = loadscale();
    }
}
