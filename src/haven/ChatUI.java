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

import java.io.*;
import java.util.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.font.TextAttribute;
import java.awt.font.TextHitInfo;
import java.awt.image.BufferedImage;
import java.text.*;
import java.text.AttributedCharacterIterator.Attribute;
import java.net.URL;
import java.util.regex.*;
import java.awt.datatransfer.*;

public class ChatUI extends Widget {
    public static final RichText.Foundry fnd = new RichText.Foundry(new ChatParser(TextAttribute.FONT, Text.dfont.deriveFont(UI.scale(12f)), TextAttribute.FOREGROUND, Color.BLACK));
    public static final Text.Foundry qfnd = new Text.Foundry(Text.dfont, 12, new java.awt.Color(192, 255, 192));
    public static final int selw = UI.scale(130);
    public static final Coord marg = UI.scale(new Coord(9, 9));
    public static final int offset = UI.scale(28);
    public static final Color[] urgcols = new Color[] {
	null,
	new Color(0, 128, 255),
	new Color(255, 128, 0),
	new Color(255, 0, 0),
    };
    public Channel sel = null;
    public int urgency = 0;
    private final Selector chansel;
    private Coord base = Coord.z;
    private QuickLine qline = null;
    private final LinkedList<Notification> notifs = new LinkedList<Notification>();
    private UI.Grab qgrab;

    public ChatUI(int w, int h) {
	super(new Coord(w, h));
	chansel = add(new Selector(new Coord(selw, sz.y - marg.y)), marg);
	setfocusctl(true);
	setcanfocus(true);
	if(h < 1)
	    hide();
    }

    protected void added() {
	base = this.c;
	resize(this.sz);
    }
    
    public static class ChatAttribute extends Attribute {
	private ChatAttribute(String name) {
	    super(name);
	}

	public static final Attribute HYPERLINK = new ChatAttribute("hyperlink");
    }
    
    public static class FuckMeGentlyWithAChainsaw {
	/* This wrapper class exists to work around the possibly most
	 * stupid Java bug ever (and that's saying a lot): That
	 * URL.equals and URL.hashCode do DNS lookups and
	 * block. Which, of course, not only sucks performance-wise
	 * but also breaks actual correct URL equality. */
	public final URL url;
	
	public FuckMeGentlyWithAChainsaw(URL url) {
	    this.url = url;
	}
    }

    public static class ChatParser extends RichText.Parser {
	public static final Pattern urlpat = Pattern.compile("\\b((https?://)|(www\\.[a-z0-9_.-]+\\.[a-z0-9_.-]+))[a-z0-9/_.~#%+?&:*=-]*", Pattern.CASE_INSENSITIVE);
	public static final Map<? extends Attribute, ?> urlstyle = RichText.fillattrs(TextAttribute.FOREGROUND, new Color(64, 64, 255),
										      TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
	
	public ChatParser(Object... args) {
	    super(args);
	}
	
	protected RichText.Part text(PState s, String text, Map<? extends Attribute, ?> attrs) throws IOException {
	    RichText.Part ret = null;
	    int p = 0;
	    while(true) {
		Matcher m = urlpat.matcher(text);
		if(!m.find(p))
		    break;
		URL url;
		try {
		    String su = text.substring(m.start(), m.end());
		    if(su.indexOf(':') < 0)
			su = "http://" + su;
		    url = new URL(su);
		} catch(java.net.MalformedURLException e) {
		    p = m.end();
		    continue;
		}
		RichText.Part lead = new RichText.TextPart(text.substring(p, m.start()), attrs);
		if(ret == null) ret = lead; else ret.append(lead);
		Map<Attribute, Object> na = new HashMap<Attribute, Object>(attrs);
		na.putAll(urlstyle);
		na.put(ChatAttribute.HYPERLINK, new FuckMeGentlyWithAChainsaw(url));
		ret.append(new RichText.TextPart(text.substring(m.start(), m.end()), na));
		p = m.end();
	    }
	    if(ret == null)
		ret = new RichText.TextPart(text, attrs);
	    else
		ret.append(new RichText.TextPart(text.substring(p), attrs));
	    return(ret);
	}
    }

    public static abstract class Channel extends Widget {
	public final List<Message> msgs = new LinkedList<Message>();
	private final Scrollbar sb;
	private final IButton cb;
	public int urgency = 0;
	private PrintWriter log;
	
	public boolean process(String msg) {
	    Pattern highlight = Pattern.compile("^@(-?\\d+)$");
	    Matcher matcher = highlight.matcher(msg);
	    if(matcher.matches()){
	        try {
		    Gob gob = ui.gui.map.glob.oc.getgob(Long.parseLong(matcher.group(1)));
		    if (gob != null) {
		        gob.highlight();
		    	return false;
		    }
		} catch (Exception ignored){}
	    }
	    return true;
	}
	
	public static abstract class Message {
	    public final double time = Utils.ntime();
	    
	    public abstract Text text();
	    public abstract Tex tex();
	    public abstract Coord sz();
	}
	
	public static class SimpleMessage extends Message {
	    private final Text t;
	    
	    public SimpleMessage(String text, Color col, int w) {
		if(CFG.SHOW_CHAT_TIMESTAMP.get()){
		    text = Utils.timestamp(text);
		}
		if(col == null)
		    this.t = fnd.render(RichText.Parser.quote(text), w);
		else
		    this.t = fnd.render(RichText.Parser.quote(text), w, TextAttribute.FOREGROUND, col);
	    }
	    
	    public Text text() {
		return(t);
	    }

	    public Tex tex() {
		return(t.tex());
	    }
	    
	    public Coord sz() {
		return(t.sz());
	    }
	}

	public Channel(boolean closable) {
	    sb = add(new Scrollbar(0, 0, 0));
	    if(closable)
		cb = add(new IButton("gfx/hud/chat-close", "", "-d", "-h"));
	    else
		cb = null;
	}

	protected void createLog() {
	    closeLog();
	    if(name() == null) {
		return;
	    }
	    BufferedWriter bw;
	    FileWriter fw;
	    try {
		File file = Config.getFile(String.format("chats/%s.txt", name()));
		//noinspection ResultOfMethodCallIgnored
		file.getParentFile().mkdirs();
		fw = new FileWriter(file, true);
		bw = new BufferedWriter(fw);
		log = new PrintWriter(bw);
		String date = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss").format(new Date(System.currentTimeMillis()));
		log.println(String.format("----- NEW SESSION (%s) -----", date));
		log.flush();
	    } catch (IOException ignored) {
	    }
	}

	protected void log(Message msg) {
	    if(log == null) {
		createLog();
	    }
	    if(log != null) {
		String text = msg.text().text;
		log.println(text);
		log.flush();
	    }
	}

	protected void closeLog() {
	    if(log != null) {
		log.flush();
		log.close();
	    }
	    log = null;
	}

	@Override
	public void destroy() {
	    super.destroy();
	    closeLog();
	}

	public void append(Message msg) {
	    synchronized(msgs) {
		msgs.add(msg);
		int y = 0;
		for(Message m : msgs)
		    y += m.sz().y;
		boolean b = sb.val >= sb.max;
		sb.max = y - ih();
		if(b)
		    sb.val = sb.max;
	    }
	    if(CFG.STORE_CHAT_LOGS.get()) {
		log(msg);
	    }
	}

	public void append(String line, Color col) {
	    append(new SimpleMessage(line, col, iw()));
	}
	
	public int iw() {
	    return(sz.x - sb.sz.x);
	}
	
	public int ih() {
	    return(sz.y);
	}
	
	public void updurgency(int urg) {
	    if(urgency != urg) {
		urgency = urg;
		int mu = 0;
		ChatUI p = getparent(ChatUI.class);
		for(Selector.DarkChannel ch : p.chansel.chls)
		    mu = Math.max(mu, ch.chan.urgency);
		p.urgency = mu;
	    }
	}

	public void draw(GOut g) {
	    g.chcolor(0, 0, 0, 128);
	    g.frect(Coord.z, sz);
	    g.chcolor();
	    int y = 0;
	    boolean sel = false;
	    synchronized(msgs) {
		for(Message msg : msgs) {
		    if((selstart != null) && (msg == selstart.msg))
			sel = true;
		    int y1 = y - sb.val;
		    int y2 = y1 + msg.sz().y;
		    if((y2 > 0) && (y1 < ih())) {
			if(sel)
			    drawsel(g, msg, y1);
			g.image(msg.tex(), new Coord(0, y1));
		    }
		    if((selend != null) && (msg == selend.msg))
			sel = false;
		    y += msg.sz().y;
		}
	    }
	    sb.max = y - ih();
	    super.draw(g);
	    updurgency(0);
	}
	
	public boolean mousewheel(Coord c, int amount) {
	    sb.ch(amount * 15);
	    return(true);
	}
	
	public void resize(Coord sz) {
	    super.resize(sz);
	    if(sb != null) {
		sb.move(new Coord(sz.x - (UI.scale(12) - marg.x), UI.scale(34) - marg.y));
		sb.resize(ih() - sb.c.y);
		int y = 0;
		for(Message m : msgs)
		    y += m.sz().y;
		boolean b = sb.val >= sb.max;
		sb.max = y - ih();
		if(b)
		    sb.val = sb.max;
	    }
	    if(cb != null) {
		cb.c = new Coord(sz.x + marg.x - cb.sz.x, -marg.y);
	    }
	}
	
	public void notify(Message msg, int urgency) {
	    getparent(ChatUI.class).notify(this, msg);
	    updurgency(Math.max(this.urgency, urgency));
	}
	
	public static class CharPos {
	    public final Message msg;
	    public final RichText.TextPart part;
	    public final TextHitInfo ch;
	    
	    public CharPos(Message msg, RichText.TextPart part, TextHitInfo ch) {
		this.msg = msg;
		this.part = part;
		this.ch = ch;
	    }
	    
	    public boolean equals(Object oo) {
		if(!(oo instanceof CharPos)) return(false);
		CharPos o = (CharPos)oo;
		return((o.msg == this.msg) && (o.part == this.part) && o.ch.equals(this.ch));
	    }
	}

	public final Comparator<CharPos> poscmp = new Comparator<CharPos>() {
	    public int compare(CharPos a, CharPos b) {
		if(a.msg != b.msg) {
		    synchronized(msgs) {
			for(Message msg : msgs) {
			    if(msg == a.msg)
				return(-1);
			    else if(msg == b.msg)
				return(1);
			}
		    }
		    throw(new IllegalStateException("CharPos message is no longer contained in the log"));
		} else if(a.part != b.part) {
		    for(RichText.Part part = ((RichText)a.msg.text()).parts; part != null; part = part.next) {
			if(part == a.part)
			    return(-1);
			else
			    return(1);
		    }
		    throw(new IllegalStateException("CharPos is no longer contained in the log"));
		} else {
		    return(a.ch.getInsertionIndex() - b.ch.getInsertionIndex());
		}
	    }
	};

	public Message messageat(Coord c, Coord hc) {
	    int y = -sb.val;
	    synchronized(msgs) {
		for(Message msg : msgs) {
		    Coord sz = msg.sz();
		    if((c.y >= y) && (c.y < y + sz.y)) {
			if(hc != null) {
			    hc.x = c.x;
			    hc.y = c.y - y;
			}
			return(msg);
		    }
		    y += sz.y;
		}
	    }
	    return(null);
	}
	
	public CharPos charat(Coord c) {
	    if(c.y < -sb.val) {
		if(msgs.size() < 1)
		    return(null);
		Message msg = msgs.get(0);
		if(!(msg.text() instanceof RichText))
		    return(null);
		RichText.TextPart fp = null;
		for(RichText.Part part = ((RichText)msg.text()).parts; part != null; part = part.next) {
		    if(part instanceof RichText.TextPart) {
			fp = (RichText.TextPart)part;
			break;
		    }
		}
		if(fp == null)
		    return(null);
		return(new CharPos(msg, fp, TextHitInfo.leading(0)));
	    }

	    Coord hc = new Coord();
	    Message msg = messageat(c, hc);
	    if((msg == null) || !(msg.text() instanceof RichText))
		return(null);
	    RichText rt = (RichText)msg.text();
	    RichText.Part p = rt.partat(hc);
	    if(p == null) {
		RichText.TextPart lp = null;
		for(RichText.Part part = ((RichText)msg.text()).parts; part != null; part = part.next) {
		    if(part instanceof RichText.TextPart)
			lp = (RichText.TextPart)part;
		}
		if(lp == null) return(null);
		return(new CharPos(msg, lp, TextHitInfo.trailing(lp.end - lp.start - 1)));
	    }
	    if(!(p instanceof RichText.TextPart))
		return(null);
	    RichText.TextPart tp = (RichText.TextPart)p;
	    return(new CharPos(msg, tp, tp.charat(hc)));
	}

	private CharPos selorig, lasthit, selstart, selend;
	private UI.Grab grab;
	private boolean dragging;
	public boolean mousedown(Coord c, int btn) {
	    if(super.mousedown(c, btn))
		return(true);
	    if(btn == 1) {
		selstart = selend = null;
		CharPos ch = charat(c);
		if(ch != null) {
		    selorig = lasthit = ch;
		    dragging = false;
		    grab = ui.grabmouse(this);
		}
		return(true);
	    }
	    return(false);
	}
	
	public void mousemove(Coord c) {
	    if(selorig != null) {
		CharPos ch = charat(c);
		if((ch != null) && !ch.equals(lasthit)) {
		    lasthit = ch;
		    if(!dragging && !ch.equals(selorig))
			dragging = true;
		    int o = poscmp.compare(selorig, ch);
		    if(o < 0) {
			selstart = selorig; selend = ch;
		    } else if(o > 0) {
			selstart = ch; selend = selorig;
		    } else {
			selstart = selend = null;
		    }
		}
	    } else {
		super.mousemove(c);
	    }
	}
	
	protected void selected(CharPos start, CharPos end) {
	    StringBuilder buf = new StringBuilder();
	    synchronized(msgs) {
		boolean sel = false;
		for(Message msg : msgs) {
		    if(!(msg.text() instanceof RichText))
			continue;
		    RichText rt = (RichText)msg.text();
		    RichText.Part part = null;
		    if(sel) {
			part = rt.parts;
		    } else if(msg == start.msg) {
			sel = true;
			for(part = rt.parts; part != null; part = part.next) {
			    if(part == start.part)
				break;
			}
		    }
		    if(sel) {
			for(; part != null; part = part.next) {
			    if(!(part instanceof RichText.TextPart))
				continue;
			    RichText.TextPart tp = (RichText.TextPart)part;
			    CharacterIterator iter = tp.ti();
			    int sch;
			    if(tp == start.part)
				sch = tp.start + start.ch.getInsertionIndex();
			    else
				sch = tp.start;
			    int ech;
			    if(tp == end.part)
				ech = tp.start + end.ch.getInsertionIndex();
			    else
				ech = tp.end;
			    for(int i = sch; i < ech; i++)
				buf.append(iter.setIndex(i));
			    if(part == end.part) {
				sel = false;
				break;
			    }
			    buf.append(' ');
			}
			if(sel)
			    buf.append('\n');
		    }
		    if(msg == end.msg)
			break;
		}
	    }
	    Clipboard cl;
	    if((cl = java.awt.Toolkit.getDefaultToolkit().getSystemSelection()) == null)
		cl = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
	    try {
		final CharPos ownsel = selstart;
		cl.setContents(new StringSelection(buf.toString()),
			       new ClipboardOwner() {
			public void lostOwnership(Clipboard cl, Transferable tr) {
			    if(selstart == ownsel)
				selstart = selend = null;
			}
		    });
	    } catch(IllegalStateException e) {}
	}

	protected void clicked(CharPos pos) {
	    AttributedCharacterIterator inf = pos.part.ti();
	    inf.setIndex(pos.ch.getCharIndex() + pos.part.start);
	    FuckMeGentlyWithAChainsaw url = (FuckMeGentlyWithAChainsaw)inf.getAttribute(ChatAttribute.HYPERLINK);
	    if((url != null) && (WebBrowser.self != null)) {
		try {
		    WebBrowser.self.show(url.url);
		} catch(WebBrowser.BrowserException e) {
		    getparent(GameUI.class).error("Could not launch web browser.");
		}
	    }
	}

	public boolean mouseup(Coord c, int btn) {
	    if(btn == 1) {
		if(selorig != null) {
		    if(selstart != null)
			selected(selstart, selend);
		    else
			clicked(selorig);
		    grab.remove();
		    selorig = null;
		    dragging = false;
		}
	    }
	    return(super.mouseup(c, btn));
	}

	public void select() {
	    getparent(ChatUI.class).select(this);
	}

	public void display() {
	    select();
	    ChatUI chat = getparent(ChatUI.class);
	    chat.expand();
	    chat.parent.setfocus(chat);
	}

	private void drawsel(GOut g, Message msg, int y) {
	    RichText rt = (RichText)msg.text();
	    boolean sel = msg != selstart.msg;
	    for(RichText.Part part = rt.parts; part != null; part = part.next) {
		if(!(part instanceof RichText.TextPart))
		    continue;
		RichText.TextPart tp = (RichText.TextPart)part;
		if(tp.start == tp.end)
		    continue;
		TextHitInfo a, b;
		if(sel) {
		    a = TextHitInfo.leading(0);
		} else if(tp == selstart.part) {
		    a = selstart.ch;
		    sel = true;
		} else {
		    continue;
		}
		if(tp == selend.part) {
		    sel = false;
		    b = selend.ch;
		} else {
		    b = TextHitInfo.trailing(tp.end - tp.start - 1);
		}
		Coord ul = new Coord(tp.x + (int)tp.advance(0, a.getInsertionIndex()), tp.y + y);
		Coord sz = new Coord((int)tp.advance(a.getInsertionIndex(), b.getInsertionIndex()), tp.height());
		g.chcolor(0, 0, 255, 255);
		g.frect(ul, sz);
		g.chcolor();
		if(!sel)
		    break;
	    }
	}

	public void uimsg(String name, Object... args) {
	    if(name == "sel") {
		select();
	    } else if(name == "dsp") {
		display();
	    } else {
		super.uimsg(name, args);
	    }
	}

	public void wdgmsg(Widget sender, String msg, Object... args) {
	    if(sender == cb) {
		wdgmsg("close");
	    } else {
		super.wdgmsg(sender, msg, args);
	    }
	}

	public abstract String name();

	private Indir<Resource> iconres = null;
	public Channel icon(Indir<Resource> res) {iconres = res; return(this);}
	public Resource.Image icon() {
	    return((iconres == null) ? null : iconres.get().layer(Resource.imgc));
	}
    }
    
    public static class Log extends Channel {
	private final String name;
	
	public Log(String name) {
	    super(false);
	    this.name = name;
	}
	
	public String name() {return(name);}
    }
    
    public static abstract class EntryChannel extends Channel {
	private final TextEntry in;
	private List<String> history = new ArrayList<String>();
	private int hpos = 0;
	private String hcurrent;
	
	public EntryChannel(boolean closable) {
	    super(closable);
	    setfocusctl(true);
	    this.in = new TextEntry(0, "") {
		    public void activate(String text) {
			if(text.length() > 0)
			    send(text);
			settext("");
			hpos = history.size();
		    }

		    public boolean keydown(KeyEvent ev) {
			if(ConsoleHost.kb_histprev.key().match(ev)) {
			    if(hpos > 0) {
				if(hpos == history.size())
				    hcurrent = text();
				rsettext(history.get(--hpos));
			    }
			    return(true);
			} else if(ConsoleHost.kb_histnext.key().match(ev)) {
			    if(hpos < history.size()) {
				if(++hpos == history.size())
				    rsettext(hcurrent);
				else
				    rsettext(history.get(hpos));
			    }
			    return(true);
			} else {
			    return(super.keydown(ev));
			}
		    }
		};
	    add(this.in);
	}
	
	public int ih() {
	    return(sz.y - in.sz.y);
	}
	
	public void resize(Coord sz) {
	    super.resize(sz);
	    if(in != null) {
		in.c = new Coord(0, this.sz.y - in.sz.y);
		in.resize(this.sz.x);
	    }
	}
	
	public void send(String text) {
	    history.add(text);
	    wdgmsg("msg", text);
	}
    }
    
    public static class SimpleChat extends EntryChannel {
	public final String name;

	public SimpleChat(boolean closable, String name) {
	    super(closable);
	    this.name = name;
	}

	public void uimsg(String msg, Object... args) {
	    if((msg == "msg") || (msg == "log")) {
		String line = (String) args[0];
		if(process(line)) {
		    Color col = null;
		    if(args.length > 1) col = (Color) args[1];
		    if(col == null) col = Color.WHITE;
		    int urgency = (args.length > 2) ? (Integer) args[2] : 0;
		    Message cmsg = new SimpleMessage(line, col, iw());
		    append(cmsg);
		    if(urgency > 0)
			notify(cmsg, urgency);
		}
	    } else {
		super.uimsg(msg, args);
	    }
	}

	public String name() {
	    return(name);
	}
    }

    public static class MultiChat extends EntryChannel {
	public final int urgency;
	private final String name;
	private final Map<Integer, Color> pc = new HashMap<Integer, Color>();
	
	public class NamedMessage extends Message {
	    public final int from;
	    public final String text;
	    public final int w;
	    public final Color col;
	    private String cn;
	    private Text r = null;
	    
	    public NamedMessage(int from, String text, Color col, int w) {
		this.from = from;
		this.text = text;
		this.w = w;
		this.col = col;
	    }

	    public Text text() {
		BuddyWnd.Buddy b = getparent(GameUI.class).buddies.find(from);
		String nm = (b == null)?"???":(b.name);
		if((r == null) || !nm.equals(cn)) {
		    String line = RichText.Parser.quote(String.format("%s: %s", nm, text));
		    if(CFG.SHOW_CHAT_TIMESTAMP.get()) {
			line = Utils.timestamp(line);
		    }
		    r = fnd.render(line, w, TextAttribute.FOREGROUND, col);
		    cn = nm;
		}
		return(r);
	    }

	    public Tex tex() {
		return(text().tex());
	    }

	    public Coord sz() {
		if(r == null)
		    return(text().sz());
		else
		    return(r.sz());
	    }
	}

	public class MyMessage extends SimpleMessage {
	    public MyMessage(String text, int w) {
		super(text, new Color(192, 192, 255), w);
	    }
	}

	public MultiChat(boolean closable, String name, int urgency) {
	    super(closable);
	    this.name = name;
	    this.urgency = urgency;
	}
	
	private float colseq = 0;
	private Color nextcol() {
	    return(new Color(Color.HSBtoRGB(colseq = ((colseq + (float)Math.sqrt(2)) % 1.0f), 0.5f, 1.0f)));
	}

	public Color fromcolor(int from) {
	    synchronized(pc) {
		Color c = pc.get(from);
		if(c == null)
		    pc.put(from, c = nextcol());
		return(c);
	    }
	}

	public void uimsg(String msg, Object... args) {
	    if(msg == "msg") {
		Integer from = (Integer)args[0];
		String line = (String)args[1];
		if(process(line)) {
		    if(from == null) {
			append(new MyMessage(line, iw()));
		    } else {
			Message cmsg = new NamedMessage(from, line, fromcolor(from), iw());
			append(cmsg);
			if(urgency > 0)
			    notify(cmsg, urgency);
		    }
		}
	    } else {
		super.uimsg(msg, args);
	    }
	}
	
	public String name() {
	    return(name);
	}
    }
    
    public static class PartyChat extends MultiChat {
	public PartyChat() {
	    super(false, "Party", 2);
	}

	public void uimsg(String msg, Object... args) {
	    if(msg == "msg") {
		Integer from = (Integer)args[0];
		long gobid = Utils.uint32((Integer)args[1]);
		String line = (String)args[2];
		if(process(line)) {
		    Color col = Color.WHITE;
		    synchronized (ui.sess.glob.party.memb) {
			Party.Member pm = ui.sess.glob.party.memb.get(gobid);
			if(pm != null)
			    col = pm.col;
		    }
		    if(from == null) {
			append(new MyMessage(line, iw()));
		    } else {
			Message cmsg = new NamedMessage(from, line, Utils.blendcol(col, Color.WHITE, 0.5), iw());
			append(cmsg);
			if(urgency > 0)
			    notify(cmsg, urgency);
		    }
		}
	    } else {
		super.uimsg(msg, args);
	    }
	}
    }
    
    public static class PrivChat extends EntryChannel {
	private final int other;
	
	public class InMessage extends SimpleMessage {
	    public InMessage(String text, int w) {
		super(">> " + text, new Color(255, 128, 128, 255), w);
	    }
	}

	public class OutMessage extends SimpleMessage {
	    public OutMessage(String text, int w) {
		super("<< " + text, new Color(128, 128, 255, 255), w);
	    }
	}

	public PrivChat(boolean closable, int other) {
	    super(closable);
	    this.other = other;
	}

	public void uimsg(String msg, Object... args) {
	    if(msg == "msg") {
		String t = (String)args[0];
		String line = (String)args[1];
		if(process(line)) {
		    if(t.equals("in")) {
			Message cmsg = new InMessage(line, iw());
			append(cmsg);
			notify(cmsg, 3);
		    } else if(t.equals("out")) {
			append(new OutMessage(line, iw()));
		    }
		}
	    } else if(msg == "err") {
		String err = (String)args[0];
		Message cmsg = new SimpleMessage(err, Color.RED, iw());
		append(cmsg);
		notify(cmsg, 3);
	    } else {
		super.uimsg(msg, args);
	    }
	}
	
	public String name() {
	    BuddyWnd.Buddy b = getparent(GameUI.class).buddies.find(other);
	    if(b == null)
		return("???");
	    else
		return(b.name);
	}
    }
    
    @RName("schan")
    public static class $SChan implements Factory {
	public Widget create(UI ui, Object[] args) {
	    String name = (String)args[0];
	    Channel ret = new SimpleChat(false, name);
	    if(args.length > 1)
		ret.icon(ui.sess.getres((Integer)args[1]));
	    return(ret);
	}
    }
    @RName("mchat")
    public static class $MChat implements Factory {
	public Widget create(UI ui, Object[] args) {
	    String name = (String)args[0];
	    int urgency = (Integer)args[1];
	    Channel ret = new MultiChat(false, name, urgency);
	    if(args.length > 2)
		ret.icon(ui.sess.getres((Integer)args[2]));
	    return(ret);
	}
    }
    @RName("pchat")
    public static class $PChat implements Factory {
	public Widget create(UI ui, Object[] args) {
	    Channel ret = new PartyChat();
	    if(args.length > 0)
		ret.icon(ui.sess.getres((Integer)args[0]));
	    return(ret);
	}
    }
    @RName("pmchat")
    public static class $PMChat implements Factory {
	public Widget create(UI ui, Object[] args) {
	    int other = (Integer)args[0];
	    Channel ret = new PrivChat(true, other);
	    if(args.length > 1)
		ret.icon(ui.sess.getres((Integer)args[1]));
	    return(ret);
	}
    }

    public void addchild(Widget child, Object... args) {
	add(child);
    }

    public <T extends Widget> T add(T w) {
	if(w instanceof Channel) {
	    Channel chan = (Channel)w;
	    chan.c = chansel.c.add(chansel.sz.x, 0);
	    chan.resize(sz.x - marg.x - chan.c.x, sz.y - chan.c.y);
	    super.add(w);
	    select(chan, false);
	    chansel.add(chan);
	    return(w);
	} else {
	    return(super.add(w));
	}
    }

    public void cdestroy(Widget w) {
	if(w instanceof Channel) {
	    Channel chan = (Channel)w;
	    if(chan == sel)
		sel = null;
	    chansel.rm(chan);
	}
    }
    
    private static final Tex chandiv = Resource.loadtex("gfx/hud/chat-cdiv");
    private static final Tex chanseld = Resource.loadtex("gfx/hud/chat-csel");
    private class Selector extends Widget {
	public final BufferedImage ctex = Resource.loadimg("gfx/hud/chantex");
	public final Text.Foundry tf = new Text.Foundry(Text.serif.deriveFont(Font.BOLD, UI.scale(12))).aa(true);
	public final Color[] uc = {
	    new Color(80, 40, 0),
	    new Color(0, 128, 255),
	    new Color(255, 128, 0),
	    new Color(255, 0, 0),
	};
	private final List<DarkChannel> chls = new ArrayList<DarkChannel>();
	private final int iconsz = UI.scale(16), ellw = tf.strsize("...").x, maxnmw = selw - iconsz;
	private int s = 0;
	
	private Text namedeco(String name, BufferedImage img, Color col) {
	    PUtils.tilemod(img.getRaster(), ctex.getRaster(), Coord.z);
	    img = PUtils.rasterimg(PUtils.blurmask2(img.getRaster(), 1, 1, col));
	    return(new Text(name, img));
	}

	public Text nmrender(String name, Color col) {
	    return(namedeco(name, tf.render(name).img, col));
	}

	private class DarkChannel {
	    public final Channel chan;
	    public Text rname;
	    public Tex ricon;
	    private int urgency = 0;
	    private Resource.Image icon;
	    
	    private DarkChannel(Channel chan) {
		this.chan = chan;
	    }

	    public Text rname() {
		String name = chan.name();
		int urg = chan.urgency;
		if((rname == null) || !rname.text.equals(name) || (urgency != urg)) {
		    Text.Line raw = tf.render(name);
		    if(raw.sz().x > maxnmw) {
			int len = raw.charat(maxnmw - ellw);
			raw = tf.render(name.substring(0, len) + "...");
		    }
		    BufferedImage img = raw.img;
		    rname = namedeco(name, img, uc[urgency = urg]);
		}
		return(rname);
	    }

	    public Tex ricon() {
		if((ricon == null) || (icon != chan.icon())) {
		    Resource.Image img = chan.icon();
		    if(img == null)
			return(null);
		    Coord sz;
		    if(img.sz.x > img.sz.y)
			sz = Coord.of(iconsz, (iconsz * img.sz.y) / img.sz.x);
		    else
			sz = Coord.of((iconsz * img.sz.x) / img.sz.y, iconsz);
		    ricon = new TexI(PUtils.uiscale(img.img, sz));
		    icon = img;
		}
		return(ricon);
	    }
	}
	
	public Selector(Coord sz) {
	    super(sz);
	}
	
	private void add(Channel chan) {
	    synchronized(chls) {
		chls.add(new DarkChannel(chan));
	    }
	}
	
	private void rm(Channel chan) {
	    synchronized(chls) {
		for(Iterator<DarkChannel> i = chls.iterator(); i.hasNext();) {
		    DarkChannel c = i.next();
		    if(c.chan == chan)
			i.remove();
		}
	    }
	}
	
	public void draw(GOut g) {
	    int i = s;
	    int y = 0;
	    synchronized(chls) {
		while(i < chls.size()) {
		    DarkChannel ch = chls.get(i);
		    if(ch.chan == sel)
			g.image(chanseld, Coord.of(0, y));
		    Tex name = ch.rname().tex(), icon = null;
		    try {
			icon = ch.ricon();
		    } catch(Loading l) {}
		    int my = y + UI.scale(8);
		    if(icon == null) {
			g.aimage(name, Coord.of(sz.x / 2, my), 0.5, 0.5);
		    } else {
			int w = name.sz().x + icon.sz().x;
			int x = (sz.x - w) / 2;
			g.aimage(icon, Coord.of(x, my), 0.0, 0.5); x += icon.sz().x;
			g.aimage(name, Coord.of(x, my), 0.0, 0.5);
		    }
		    g.image(chandiv, Coord.of(0, y + UI.scale(18)));
		    y += offset;
		    if(y >= sz.y)
			break;
		    i++;
		}
	    }
	}
	
	public boolean up() {
	    Channel prev = null;
	    for(DarkChannel ch : chls) {
		if(ch.chan == sel) {
		    if(prev != null) {
			select(prev);
			return(true);
		    } else {
			return(false);
		    }
		}
		prev = ch.chan;
	    }
	    return(false);
	}
	
	public boolean down() {
	    for(Iterator<DarkChannel> i = chls.iterator(); i.hasNext();) {
		DarkChannel ch = i.next();
		if(ch.chan == sel) {
		    if(i.hasNext()) {
			select(i.next().chan);
			return(true);
		    } else {
			return(false);
		    }
		}
	    }
	    return(false);
	}
	
	private Channel bypos(Coord c) {
	    int i = (c.y / offset) + s;
	    if((i >= 0) && (i < chls.size()))
		return(chls.get(i).chan);
	    return(null);
	}

	public boolean mousedown(Coord c, int button) {
	    if(button == 1) {
		Channel chan = bypos(c);
		if(chan != null)
		    select(chan);
	    }
	    return(true);
	}
	
	public boolean mousewheel(Coord c, int amount) {
	    if(!ui.modshift) {
		s += amount;
		if(s >= chls.size() - (sz.y / offset))
		    s = chls.size() - (sz.y / offset);
		if(s < 0)
		    s = 0;
	    } else {
		if(amount < 0)
		    up();
		else if(amount > 0)
		    down();
	    }
	    return(true);
	}
    }
    
    public void select(Channel chan, boolean focus) {
	Channel prev = sel;
	sel = chan;
	if(prev != null)
	    prev.hide();
	sel.show();
	resize(sz);
	if(focus || hasfocus)
	    setfocus(chan);
    }

    public void select(Channel chan) {
	select(chan, true);
    }

    private class Notification {
	public final Channel chan;
	public final Text chnm;
	public final Channel.Message msg;
	public final double time = Utils.ntime();
	
	private Notification(Channel chan, Channel.Message msg) {
	    this.chan = chan;
	    this.msg = msg;
	    this.chnm = chansel.nmrender(chan.name(), Color.BLACK);
	}
    }

    private Text.Line rqline = null;
    private int rqpre;
    public void drawsmall(GOut g, Coord br, int h) {
	Coord c;
	if(qline != null) {
	    if((rqline == null) || !qline.buf.lneq(rqline.text)) {
		String pre = String.format("%s> ", qline.chan.name());
		rqline = qfnd.render(pre + qline.buf.line());
		rqpre = pre.length();
	    }
	    int point = qline.buf.point(), mark = qline.buf.mark();
	    int px = rqline.advance(point + rqpre) + UI.scale(1);
	    if(mark >= 0) {
		int mx = rqline.advance(mark + rqpre) + UI.scale(1);
		g.chcolor(TextEntry.selcol);
		g.frect2(Coord.of(br.x + Math.min(px, mx), br.y - UI.scale(18)),
			 Coord.of(br.x + Math.max(px, mx), br.y - UI.scale(6)));
		g.chcolor();
	    }
	    c = br.sub(UI.scale(0, 20));
	    g.image(rqline.tex(), c);
	    g.line(Coord.of(br.x + px, br.y - UI.scale(18)), Coord.of(br.x + px, br.y - UI.scale(6)), 1);
	} else {
	    c = br.sub(UI.scale(0, 5));
	}
	double now = Utils.ntime();
	synchronized(notifs) {
	    for(Iterator<Notification> i = notifs.iterator(); i.hasNext();) {
		Notification n = i.next();
		if(now - n.time > 5.0) {
		    i.remove();
		    continue;
		}
		if((c.y -= n.msg.sz().y) < br.y - h)
		    break;
		g.image(n.chnm.tex(), c, br.sub(0, h), br.add(selw - UI.scale(10), 0));
		g.image(n.msg.tex(), c.add(selw, 0));
	    }
	}
    }

    private static final Tex bulc = Resource.loadtex("gfx/hud/chat-lc");
    private static final Tex burc = Resource.loadtex("gfx/hud/chat-rc");
    private static final Tex bhb = Resource.loadtex("gfx/hud/chat-hori");
    private static final Tex bvlb = Resource.loadtex("gfx/hud/chat-verti");
    private static final Tex bvrb = bvlb;
    private static final Tex bmf = Resource.loadtex("gfx/hud/chat-mid");
    private static final Tex bcbd = Resource.loadtex("gfx/hud/chat-close-g");
    public void draw(GOut g) {
	g.rimage(Window.bg, marg, sz.sub(marg.x * 2, marg.y));
	super.draw(g);
	g.image(bulc, new Coord(0, 0));
	g.image(burc, new Coord(sz.x - burc.sz().x, 0));
	g.rimagev(bvlb, new Coord(0, bulc.sz().y), sz.y - bulc.sz().y);
	g.rimagev(bvrb, new Coord(sz.x - bvrb.sz().x, burc.sz().y), sz.y - burc.sz().y);
	g.rimageh(bhb, new Coord(bulc.sz().x, 0), sz.x - bulc.sz().x - burc.sz().x);
	g.aimage(bmf, new Coord(sz.x / 2, 0), 0.5, 0);
	if((sel == null) || (sel.cb == null))
	    g.aimage(bcbd, new Coord(sz.x, 0), 1, 0);
    }

    private static final Resource notifsfx = Resource.local().loadwait("sfx/hud/chat");
    public void notify(Channel chan, Channel.Message msg) {
	synchronized(notifs) {
	    notifs.addFirst(new Notification(chan, msg));
	}
	ui.sfx(notifsfx);
    }

    private class Spring extends NormAnim {
	final int oh = sz.y, nh;
	Spring(int nh) {
	    super(0.15);
	    this.nh = nh;
	    show();
	}

	public void ntick(double a) {
	    double b = Math.cos(Math.PI * 2.5 * a) * Math.exp(-5 * a);
	    resize(sz.x, nh + (int)((nh - oh) * b));
	    if((a == 1.0) && (nh == 0)) {
		hide();
	    }
	}
    }

    public void resize(Coord sz) {
	super.resize(sz);
	this.c = base.add(0, -this.sz.y);
	chansel.resize(new Coord(selw, this.sz.y - marg.y));
	if(sel != null)
	    sel.resize(new Coord(this.sz.x - marg.x - sel.c.x, this.sz.y - sel.c.y));
    }

    public int targeth = sz.y;
    public void sresize(int h) {
	clearanims(Spring.class);
	new Spring(targeth = h);
    }

    public void hresize(int h) {
	clearanims(Spring.class);
	resize(sz.x, targeth = h);
    }

    public void resize(int w) {
	resize(new Coord(Math.max(w, selw + marg.x + UI.scale(10) + marg.x), sz.y));
    }
    
    public void move(Coord base) {
	this.c = (this.base = base).add(0, -sz.y);
    }

    public void expand() {
	if(!visible)
	    sresize(savedh);
    }

    private class QuickLine implements ReadLine.Owner {
	public final ReadLine buf;
	public final EntryChannel chan;
	
	private QuickLine(EntryChannel chan) {
	    this.buf = ReadLine.make(this, "");
	    this.chan = chan;
	}
	
	private void cancel() {
	    qline = null;
	    qgrab.remove();
	}
	
	public void done(ReadLine buf) {
	    if(!buf.empty())
		chan.send(buf.line());
	    cancel();
	}

	public boolean key(KeyEvent ev) {
	    if(key_esc.match(ev)) {
		cancel();
		return(true);
	    } else {
		return(buf.key(ev));
	    }
	}
    }

    private UI.Grab dm = null;
    private Coord doff;
    private static final int minh = 111;
    public int savedh = UI.scale(Math.max(minh, Utils.getprefi("chatsize", minh)));
    public boolean mousedown(Coord c, int button) {
	int bmfx = (sz.x - bmf.sz().x) / 2;
	if((button == 1) && (c.y < bmf.sz().y) && (c.x >= bmfx) && (c.x <= (bmfx + bmf.sz().x))) {
	    dm = ui.grabmouse(this);
	    doff = c;
	    return(true);
	} else {
	    return(super.mousedown(c, button));
	}
    }

    public void mousemove(Coord c) {
	if(dm != null) {
	    resize(sz.x, savedh = Math.max(UI.scale(minh), sz.y + doff.y - c.y));
	} else {
	    super.mousemove(c);
	}
    }

    public boolean mouseup(Coord c, int button) {
	if(dm != null) {
	    dm.remove();
	    dm = null;
	    Utils.setprefi("chatsize", UI.unscale(savedh));
	    return(true);
	} else {
	    return(super.mouseup(c, button));
	}
    }

    public boolean keydown(KeyEvent ev) {
	boolean M = (ev.getModifiersEx() & (KeyEvent.META_DOWN_MASK | KeyEvent.ALT_DOWN_MASK)) != 0;
	if(qline != null) {
	    if(M && (ev.getKeyCode() == KeyEvent.VK_UP)) {
		Channel prev = this.sel;
		while(chansel.up()) {
		    if(this.sel instanceof EntryChannel)
			break;
		}
		if(!(this.sel instanceof EntryChannel)) {
		    select(prev);
		    return(true);
		}
		qline = new QuickLine((EntryChannel)sel);
		return(true);
	    } else if(M && (ev.getKeyCode() == KeyEvent.VK_DOWN)) {
		Channel prev = this.sel;
		while(chansel.down()) {
		    if(this.sel instanceof EntryChannel)
			break;
		}
		if(!(this.sel instanceof EntryChannel)) {
		    select(prev);
		    return(true);
		}
		qline = new QuickLine((EntryChannel)sel);
		return(true);
	    }
	    qline.key(ev);
	    return(true);
	} else {
	    if(M && (ev.getKeyCode() == KeyEvent.VK_UP)) {
		chansel.up();
		return(true);
	    } else if(M && (ev.getKeyCode() == KeyEvent.VK_DOWN)) {
		chansel.down();
		return(true);
	    }
	    return(super.keydown(ev));
	}
    }

    public static final KeyBinding kb_quick = KeyBinding.get("chat-quick", KeyMatch.forcode(KeyEvent.VK_ENTER, 0));
    public boolean globtype(char key, KeyEvent ev) {
	if(kb_quick.key().match(ev)) {
	    if(!visible && (sel instanceof EntryChannel)) {
		qgrab = ui.grabkeys(this);
		qline = new QuickLine((EntryChannel)sel);
		return(true);
	    }
	}
	return(super.globtype(key, ev));
    }
}
