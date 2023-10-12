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
import integrations.mapv4.MappingClient;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.*;
import java.io.*;
import java.nio.file.*;

public class Config {
    public static final File HOMEDIR = new File("").getAbsoluteFile();
    public static final String LINE_SEPARATOR = System.getProperty("line.separator");
    public static final Properties jarprops = getjarprops();
    public static final String confid = jarprops.getProperty("config.client-id", "unknown");
    public static final Variable<Boolean> par = Variable.def(() -> true);
	public final Properties localprops = getlocalprops();

    
    private static Config global = null;
    public static Config get() {
	if(global != null)
	    return(global);
	synchronized(Config.class) {
	    if(global == null)
		global = new Config();
	    return(global);
	}
    }
    
    public static String version;
    public static final boolean isUpdate;
    public static boolean center_tile = false;
    private static String username, playername;
    public static boolean always_true = true; //always true to facilitate changes with minimal intrusions into loftar's code
    
    static {
	loadBuildVersion();
	isUpdate = !CFG.VERSION.get().equals(version) || !getFile("changelog.txt").exists();
	if(isUpdate){
	    CFG.VERSION.set(version);
	}
    }
    
    private static void loadBuildVersion() {
	InputStream in = Config.class.getResourceAsStream("/buildinfo");
	try {
	    try {
		if(in != null) {
		    Properties info = new Properties();
		    info.load(in);
		    version = info.getProperty("version");
		}
	    } finally {
		if (in != null) { in.close(); }
	    }
	} catch(IOException e) {
	    throw(new Error(e));
	}
    }
    
    public static File getFile(String name) {
	return new File(HOMEDIR, name);
    }
    
    public static String loadFile(String name) {
	InputStream inputStream = getFSStream(name);
	if(inputStream == null) {
	    inputStream = getJarStream(name);
	}
	return getString(inputStream);
    }
    
    public static String loadJarFile(String name) {
	return getString(getJarStream(name));
    }
    
    public static String loadFSFile(String name) {
	return getString(getFSStream(name));
    }
    
    private static InputStream getFSStream(String name) {
	InputStream inputStream = null;
	File file = Config.getFile(name);
	if(file.exists() && file.canRead()) {
	    try {
		inputStream = new FileInputStream(file);
	    } catch (FileNotFoundException ignored) {
	    }
	}
	return inputStream;
    }
    
    private static InputStream getJarStream(String name) {
	if(name.charAt(0) != '/') {
	    name = '/' + name;
	}
	return Config.class.getResourceAsStream(name);
    }
    
    private static String getString(InputStream inputStream) {
	if(inputStream != null) {
	    try {
		return Utils.stream2str(inputStream);
	    } catch (Exception ignore) {
	    } finally {
		try {inputStream.close();} catch (IOException ignored) {}
	    }
	}
	return null;
    }
    
    public static void saveFile(String name, String data) {
	File file = Config.getFile(name);
	boolean exists = file.exists();
	if(!exists) {
	    try {
		String parent = file.getParent();
		//noinspection ResultOfMethodCallIgnored
		new File(parent).mkdirs();
		exists = file.createNewFile();
	    } catch (IOException ignored) {}
	}
	if(exists && file.canWrite()) {
	    try (FileOutputStream fos = new FileOutputStream(file);
		 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
		 BufferedWriter writer = new BufferedWriter(osw)) {
		writer.write(data);
	    } catch (IOException e) {
		e.printStackTrace();
	    }
	}
    }

    private static Properties getjarprops() {
	Properties ret = new Properties();
	try(InputStream fp = Config.class.getResourceAsStream("boot-props")) {
	    if(fp != null)
		ret.load(fp);
	} catch(Exception exc) {
	    /* XXX? Catch all exceptions? It just seems dumb to
	     * potentially crash here for unforeseen reasons. */
	    new Warning(exc, "unexpected error occurred when loading local properties").issue();
	}
	return(ret);
    }

    private static Properties getlocalprops() {
	Properties ret = new Properties();
	try {
	    Path jar = Utils.srcpath(Config.class);
	    if(jar != null) {
		try(InputStream fp = Files.newInputStream(jar.resolveSibling("haven-config.properties"))) {
		    ret.load(fp);
		} catch(NoSuchFileException exc) {
		    /* That's quite alright. */
		}
	    }
	} catch(Exception exc) {
	    new Warning(exc, "unexpected error occurred when loading neighboring properties").issue();
	}
	return(ret);
    }

    public String getprop(String name, String def) {
	String ret;
	if((ret = jarprops.getProperty(name)) != null)
	    return(ret);
	if((ret = localprops.getProperty(name)) != null)
	    return(ret);
	return(Utils.getprop(name, def));
    }

    public static final Path parsepath(String p) {
	if((p == null) || p.equals(""))
	    return(null);
	return(Utils.path(p));
    }

    public static final URL parseurl(String url) {
	if((url == null) || url.equals(""))
	    return(null);
	try {
	    return(new URL(url));
	} catch(java.net.MalformedURLException e) {
	    throw(new RuntimeException(e));
	}
    }

    public static void parsesvcaddr(String spec, Consumer<String> host, Consumer<Integer> port) {
	if((spec.length() > 0) && (spec.charAt(0) == '[')) {
	    int p = spec.indexOf(']');
	    if(p > 0) {
		String hspec = spec.substring(1, p);
		if(spec.length() == p + 1) {
		    host.accept(hspec);
		    return;
		} else if((spec.length() > p + 1) && (spec.charAt(p + 1) == ':')) {
		    host.accept(hspec);
		    port.accept(Integer.parseInt(spec.substring(p + 2)));
		    return;
		}
	    }
	}
	int p = spec.indexOf(':');
	if(p >= 0) {
	    host.accept(spec.substring(0, p));
	    port.accept(Integer.parseInt(spec.substring(p + 1)));
	    return;
	} else {
	    host.accept(spec);
	    return;
	}
    }

    public static class Variable<T> {
	public final Function<Config, T> init;
	private boolean inited = false;
	private T val;

	private Variable(Function<Config, T> init) {
	    this.init = init;
	}

	public T get() {
	    if(!inited) {
		synchronized(this) {
		    if(!inited) {
			val = init.apply(Config.get());
			inited = true;
		    }
		}
	    }
	    return(val);
	}

	public void set(T val) {
	    synchronized(this) {
		inited = true;
		this.val = val;
	    }
	}

	public static <V> Variable<V> def(Supplier<V> defval) {
	    return(new Variable<>(cfg -> defval.get()));
	}

	public static <V> Variable<V> prop(String name, Function<String, V> parse, Supplier<V> defval) {
	    return(new Variable<>(cfg -> {
			String pv = cfg.getprop(name, null);
			return((pv == null) ? defval.get() : parse.apply(pv));
	    }));
	}

	public static Variable<String> prop(String name, String defval) {
	    return(prop(name, Function.identity(), () -> defval));
	}
	public static Variable<Integer> propi(String name, int defval) {
	    return(prop(name, Integer::parseInt, () -> defval));
	}
	public static Variable<Boolean> propb(String name, boolean defval) {
	    return(prop(name, Utils::parsebool, () -> defval));
	}
	public static Variable<Double> propf(String name, Double defval) {
	    return(prop(name, Double::parseDouble, () -> defval));
	}
	public static Variable<byte[]> propb(String name, byte[] defval) {
	    return(prop(name, Utils::hex2byte, () -> defval));
	}
	public static Variable<URL> propu(String name, URL defval) {
	    return(prop(name, Config::parseurl, () -> defval));
	}
	public static Variable<URL> propu(String name, String defval) {
	    return(propu(name, parseurl(defval)));
	}
	public static Variable<Path> propp(String name, Path defval) {
	    return(prop(name, Config::parsepath, () -> defval));
	}
	public static Variable<Path> propp(String name, String defval) {
	    return(propp(name, parsepath(defval)));
	}
    }

    private static void usage(PrintStream out) {
	out.println("usage: haven.jar [OPTIONS] [SERVER[:PORT]]");
	out.println("Options include:");
	out.println("  -h                 Display this help");
	out.println("  -d                 Display debug text");
	out.println("  -P                 Enable profiling");
	out.println("  -G                 Enable GPU profiling");
	out.println("  -f                 Fullscreen mode");
	out.println("  -U URL             Use specified external resource URL");
	out.println("  -r DIR             Use specified resource directory (or HAVEN_RESDIR)");
	out.println("  -A AUTHSERV[:PORT] Use specified authentication server");
	out.println("  -u USER            Authenticate as USER (together with -C)");
	out.println("  -C HEXCOOKIE       Authenticate with specified hex-encoded cookie");
	out.println("  -p PREFSPEC        Use alternate preference prefix");
    }

    public static void cmdline(String[] args) {
	PosixArgs opt = PosixArgs.getopt(args, "hdPGfU:r:A:u:C:p:");
	if(opt == null) {
	    usage(System.err);
	    System.exit(1);
	}
	for(char c : opt.parsed()) {
	    switch(c) {
	    case 'h':
		usage(System.out);
		System.exit(0);
		break;
	    case 'd':
		UIPanel.dbtext.set(true);
		break;
	    case 'P':
		UIPanel.profile.set(true);
		break;
	    case 'G':
		UIPanel.profilegpu.set(true);
		break;
	    case 'f':
		MainFrame.initfullscreen.set(true);
		break;
	    case 'r':
		Resource.resdir.set(Utils.path(opt.arg));
		break;
	    case 'A':
		parsesvcaddr(opt.arg, Bootstrap.authserv::set, Bootstrap.authport::set);
		break;
	    case 'U':
		try {
		    Resource.resurl.set(new URL(opt.arg));
		} catch(java.net.MalformedURLException e) {
		    System.err.println(e);
		    System.exit(1);
		}
		break;
	    case 'u':
		Bootstrap.authuser.set(opt.arg);
		break;
	    case 'C':
		Bootstrap.authck.set(Utils.hex2byte(opt.arg));
		break;
	    case 'p':
		Utils.prefspec.set(opt.arg);
		break;
	    }
	}
	if(opt.rest.length > 0)
	    parsesvcaddr(opt.rest[0], Bootstrap.defserv::set, Bootstrap.mainport::set);
	if(opt.rest.length > 1)
	    Bootstrap.servargs.set(Utils.splice(opt.rest, 1));
    }
    
    public static void setUserName(String username) {
	Config.username = username;
	Config.playername = null;
    }
    
    public static void setPlayerName(String playername) {
	Config.playername = playername;
	Reactor.PLAYER.onNext(userpath());
    }
    
    public static String userpath() {
	return String.format("%s/%s", username, playername);
    }

    static {
	Console.setscmd("par", new Console.Command() {
		public void run(Console cons, String[] args) {
		    par.set(Utils.parsebool(args[1]));
		}
	    });
    }
    
    public static void initAutomapper(UI ui) {
        if (MappingClient.initialized()) {
            MappingClient.destroy();
	}
	MappingClient.init(ui.sess.glob);
	MappingClient automapper = MappingClient.getInstance();
	automapper.SetPlayerName(playername);
	automapper.SetEndpoint(CFG.AUTOMAP_ENDPOINT.get());
	automapper.EnableGridUploads(CFG.AUTOMAP_UPLOAD.get());
	automapper.EnableTracking(CFG.AUTOMAP_TRACK.get());
    }
}
