package dolda.coe;

import java.util.*;
import java.io.*;
import java.lang.reflect.*;
import static dolda.coe.BinaryData.*;

public class BinEncoder {
    public static final java.nio.charset.Charset utf8 = java.nio.charset.Charset.forName("UTF-8");
    private final List<Object> stack = new ArrayList<>();
    private int nextref = 0;
    private Map<Object, Integer> reftab = null;
    private final Map<String, Integer> nstab = new IdentityHashMap<>();

    public BinEncoder backrefs(boolean backrefs) {
	reftab = backrefs ? new IdentityHashMap<>() : null;
	return(this);
    }

    public static int enctag(int pri, int sec) {
	return((sec << 3) | pri);
    }

    public void writeint(OutputStream dst, long x) throws IOException {
	if(x >= 0) {
	    int b = (int)(x & 0x7f);
	    x >>= 7;
	    while((x > 0) || ((b & 0x40) != 0)) {
		dst.write(0x80 | b);
		b = (int)(x & 0x7f);
		x >>= 7;
	    }
	    dst.write(b);
	} else {
	    int b = (int)(x & 0x7f);
	    x >>= 7;
	    while((x < -1) || ((b & 0x40) == 0)) {
		dst.write(0x80 | b);
		b = (int)(x & 0x7f);
		x >>= 7;
	    }
	    dst.write(b);
	}
    }

    public void writestr(OutputStream dst, String str) throws IOException {
	dst.write(str.getBytes(utf8));
	dst.write(0);
    }

    public void writesym(OutputStream dst, Symbol sym) throws IOException {
	if(sym.ns.equals("")) {
	    writetag(dst, T_STR, STR_SYM, sym);
	    writestr(dst, sym.name);
	    return;
	}
	Integer nsref = nstab.get(sym.ns);
	if(nsref != null) {
	    writetag(dst, T_SYM, 0, sym);
	    dst.write(1);
	    writeint(dst, nsref);
	    writestr(dst, sym.name);
	} else {
	    int ref = writetag(dst, T_SYM, 0, sym);
	    dst.write(0);
	    writestr(dst, sym.ns);
	    writestr(dst, sym.name);
	    nstab.put(sym.ns, ref);
	}
    }

    public void writefloat(OutputStream dst, double x) throws IOException {
	writetag(dst, T_BIT, BIT_BFLOAT, null);
	ByteArrayOutputStream buf = new ByteArrayOutputStream();
	buffered: {
	    long bits = Double.doubleToLongBits(x);
	    long sgn = (bits & 0x8000000000000000l) >>> 63;
	    long exp = (bits & 0x7ff0000000000000l) >>> 52;
	    long mnt = (bits & 0x000fffffffffffffl) >>>  0;
	    if(exp == 0x7ff) {
		writeint(buf, 0);
		if(mnt == 0) {
		    writeint(buf, (sgn == 0) ? 2 : 3);
		} else {
		    writeint(buf, 4);
		}
	    } else {
		if(exp == 0) {
		    if(mnt == 0) {
			writeint(buf, 0);
			writeint(buf, (sgn == 0) ? 0 : 1);
			break buffered;
		    }
		    exp = 12 - 0x3ff - Long.numberOfLeadingZeros(mnt);
		} else {
		    exp -= 0x3ff;
		    mnt |= 0x0010000000000000l;
		}
		while((mnt & 1l) == 0)
		    mnt >>= 1;
		if(sgn != 0)
		    mnt = -mnt;
		writeint(buf, mnt);
		writeint(buf, exp);
	    }
	}
	byte[] enc = buf.toByteArray();
	writeint(dst, enc.length);
	dst.write(enc);
    }

    public void writeseq(OutputStream dst, Iterable<?> seq) throws IOException {
	for(Object v : seq)
	    write(dst, v);
	dst.write(enctag(T_END, 0));
    }

    public void writearray(OutputStream dst, Object array) throws IOException {
	for(int i = 0, n = Array.getLength(array); i < n; i++)
	    write(dst, Array.get(array, i));
	dst.write(enctag(T_END, 0));
    }

    public void writemap(OutputStream dst, Map<?, ?> map) throws IOException {
	for(Map.Entry<?, ?> ent : map.entrySet()) {
	    write(dst, ent.getKey());
	    write(dst, ent.getValue());
	}
	dst.write(enctag(T_END, 0));
    }

    public void writeobject(OutputStream dst, Object obj) throws IOException {
	Map<Symbol, Object> data;
	try {
	    data = ObjectData.encode(obj);
	} catch(Throwable t) {
	    data = ObjectData.encode(new Unencodable(obj.getClass(), t));
	}
	writetag(dst, T_CON, CON_OBJ, obj);
	write(dst, Symbol.get("java/object", obj.getClass().getName()));
	writemap(dst, data);
    }

    private int writetag(OutputStream dst, int pri, int sec, Object datum) throws IOException {
	dst.write(enctag(pri, sec));
	if(reftab != null) {
	    int ref = nextref++;
	    if(datum != null) {
		reftab.putIfAbsent(datum, ref);
		return(ref);
	    }
	}
	return(-1);
    }

    public void write(OutputStream dst, Object datum) throws IOException {
	if(reftab != null) {
	    Integer ref = reftab.get(datum);
	    if(ref != null) {
		dst.write(enctag(T_INT, INT_REF));
		writeint(dst, ref);
		return;
	    }
	}
	for(Object prev : stack) {
	    if(prev == datum)
		throw(new RuntimeException("circular reference: " + stack + " + " + datum));
	}
	int cks = stack.size();
	stack.add(datum);
	if(datum == null) {
	    writetag(dst, T_NIL, 0, null);
	} else if(datum instanceof Boolean) {
	    writetag(dst, T_NIL, ((Boolean)datum).booleanValue() ? NIL_TRUE : NIL_FALSE, null);
	} else if((datum instanceof Byte) || (datum instanceof Short) || (datum instanceof Integer) || (datum instanceof Long)) {
	    writetag(dst, T_INT, 0, null);
	    writeint(dst, ((Number)datum).longValue());
	} else if((datum instanceof Float) || (datum instanceof Double)) {
	    writefloat(dst, ((Number)datum).doubleValue());
	} else if(datum instanceof String) {
	    writetag(dst, T_STR, 0, datum);
	    writestr(dst, (String)datum);
	} else if(datum instanceof byte[]) {
	    writetag(dst, T_BIT, 0, datum);
	    writeint(dst, ((byte[])datum).length);
	    dst.write((byte[])datum);
	} else if(datum instanceof Symbol) {
	    writesym(dst, (Symbol)datum);
	} else if(datum.getClass().isArray()) {
	    writetag(dst, T_CON, CON_SEQ, datum);
	    writearray(dst, datum);
	} else if(datum instanceof List) {
	    writetag(dst, T_CON, CON_SEQ, datum);
	    writeseq(dst, (List)datum);
	} else if(datum instanceof Collection) {
	    writetag(dst, T_CON, CON_SET, datum);
	    writeseq(dst, (Collection)datum);
	} else if(datum instanceof Map) {
	    writetag(dst, T_CON, CON_MAP, datum);
	    writemap(dst, (Map)datum);
	} else if(datum instanceof Class) {
	    writesym(dst, Symbol.get("java/class", ((Class)datum).getName()));
	} else {
	    writeobject(dst, datum);
	}
	if(stack.remove(stack.size() - 1) != datum)
	    throw(new AssertionError(stack + "[-1] != " + datum));
	if(stack.size() != cks)
	    throw(new AssertionError(stack.size() + " != " + cks));
    }
}
