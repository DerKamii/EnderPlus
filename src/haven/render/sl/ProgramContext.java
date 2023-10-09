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

package haven.render.sl;

import java.util.*;

public class ProgramContext {
    public final VertexContext vctx;
    public final FragmentContext fctx;
    public final Set<Uniform> uniforms = new HashSet<Uniform>();
    public final Set<Attribute> attribs = new HashSet<Attribute>();
    public final List<FragData> fragdata = new ArrayList<FragData>();
    public final Map<Symbol, String> symtab = new HashMap<Symbol, String>();
    public final Map<String, Symbol> rsymtab = new HashMap<String, Symbol>();
    public int symgen = 1;
    public boolean dump = false;
    public boolean instanced = false;
    private final Collection<Object> mods = new LinkedList<Object>();

    public ProgramContext() {
	vctx = new VertexContext(this);
	fctx = new FragmentContext(this);
    }

    public void module(Object mod) {
	mods.add(mod);
    }

    public <T> T getmod(Class<T> cl) {
	T ret = null;
	for(Object mod : mods) {
	    if(cl.isInstance(mod)) {
		if(ret == null)
		    ret = cl.cast(mod);
		else
		    throw(new RuntimeException("multiple modules of " + cl + " installed: " + ret + " and " + mod));
	    }
	}
	return(ret);
    }
}
