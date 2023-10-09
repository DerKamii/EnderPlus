package haven.resutil;

import haven.*;

public class CompilerClassLoader extends ClassLoader {
    private Indir<Resource>[] useres;
    
    @SuppressWarnings("unchecked")
    public CompilerClassLoader(ClassLoader parent) {
	super(parent);
	String[] useresnm = Utils.getprop("haven.resutil.classloader.useres", null).split(":");
	this.useres = new Indir[useresnm.length];
	for(int i = 0; i < useresnm.length; i++)
	    this.useres[i] = Resource.local().load(useresnm[i]);
    }
    
    public Class<?> findClass(String name) throws ClassNotFoundException {
	for(Indir<Resource> res : useres) {
	    ClassLoader loader = Loading.waitfor(() -> res.get().layer(Resource.CodeEntry.class).loader());
	    try {
		return(loader.loadClass(name));
	    } catch(ClassNotFoundException e) {}
	}
	throw(new ClassNotFoundException(name + " was not found in any of the requested resources."));
    }
}
