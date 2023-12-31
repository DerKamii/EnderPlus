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

package haven.render;

import java.nio.*;

public interface DataBuffer {
    public int size();

    public enum Usage {
	EPHEMERAL, STREAM, STATIC;
    }

    public interface Filler<T extends DataBuffer> {
	public FillBuffer fill(T buf, Environment env);
	public default void done() {}

	public static Filler<DataBuffer> of(ByteBuffer data) {
	    return((tgt, env) -> {
		    FillBuffer buf = env.fillbuf(tgt);
		    buf.pull(data.slice());
		    return(buf);
		});
	}
	public static Filler<DataBuffer> of(ShortBuffer data) {
	    return((tgt, env) -> {
		    FillBuffer buf = env.fillbuf(tgt);
		    buf.push().asShortBuffer().put(data.slice());
		    return(buf);
		});
	}
	public static Filler<DataBuffer> of(IntBuffer data) {
	    return((tgt, env) -> {
		    FillBuffer buf = env.fillbuf(tgt);
		    buf.push().asIntBuffer().put(data.slice());
		    return(buf);
		});
	}
	public static Filler<DataBuffer> of(FloatBuffer data) {
	    return((tgt, env) -> {
		    FillBuffer buf = env.fillbuf(tgt);
		    buf.push().asFloatBuffer().put(data.slice());
		    return(buf);
		});
	}
	public static Filler<DataBuffer> of(byte[] data) {
	    return(of(ByteBuffer.wrap(data)));
	}
	public static Filler<DataBuffer> of(short[] data) {
	    return((tgt, env) -> {
		    FillBuffer buf = env.fillbuf(tgt);
		    buf.push().asShortBuffer().put(data);
		    return(buf);
		});
	};
	public static Filler<DataBuffer> of(int[] data) {
	    return((tgt, env) -> {
		    FillBuffer buf = env.fillbuf(tgt);
		    buf.push().asIntBuffer().put(data);
		    return(buf);
		});
	};
	public static Filler<DataBuffer> of(float[] data) {
	    return((tgt, env) -> {
		    FillBuffer buf = env.fillbuf(tgt);
		    buf.push().asFloatBuffer().put(data);
		    return(buf);
		});
	};

	public static Filler<DataBuffer> zero() {
	    return((tgt, env) -> {
		    FillBuffer buf = env.fillbuf(tgt);
		    ByteBuffer data = buf.push();
		    for(int i = 0; i < buf.size(); i++)
			data.put(i, (byte)0);
		    return(buf);
		});
	}
    }

    public interface PartFiller<T extends DataBuffer> extends Filler<T> {
	public FillBuffer fill(T buf, Environment env, int from, int to);

	public default FillBuffer fill(T buf, Environment env) {
	    return(fill(buf, env, 0, buf.size()));
	}
    }
}
