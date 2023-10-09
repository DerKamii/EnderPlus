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

import static java.lang.Math.PI;

public class Coord3f {
    public float x, y, z;
    public static Coord3f o = new Coord3f(0, 0, 0);
    public static Coord3f xu = of(1, 0, 0);
    public static Coord3f yu = of(0, 1, 0);
    public static Coord3f zu = of(0, 0, 1);
    
    public Coord3f(float x, float y, float z) {
	this.x = x;
	this.y = y;
	this.z = z;
    }

    public Coord3f(Coord3f c) {
	this(c.x, c.y, c.z);
    }
    
    public Coord3f(Coord c) {
	this(c.x, c.y, 0);
    }

    public static Coord3f of(float x, float y, float z) {return(new Coord3f(x, y, z));}
    public static Coord3f of(Coord3f c) {return(of(c.x, c.y, c.z));}
    public static Coord3f of(HomoCoord4f c) {return(of(c.x, c.y, c.z));}
    
    public boolean equals(Coord3f o) {
	return((o.x == x) && (o.y == y) && (o.z == z));
    }

    public boolean equals(Object o) {
	return((o instanceof Coord3f) && equals((Coord3f)o));
    }

    public Coord3f add(float ax, float ay, float az) {
	return(of(x + ax, y + ay, z + az));
    }

    public Coord3f add(Coord3f b) {
	return(add(b.x, b.y, b.z));
    }
    
    public Coord3f sadd(float e, float a, float r) {
	return(add((float)Math.cos(a) * (float)Math.cos(e) * r, (float)Math.sin(a) * (float)Math.cos(e) * r, (float)Math.sin(e) * r));
    }
    
    public Coord3f neg() {
	return(of(-x, -y, -z));
    }
    
    public Coord3f sub(float ax, float ay, float az) {
	return(of(x - ax, y - ay, z - az));
    }

    public Coord3f sub(Coord3f b) {
	return(sub(b.x, b.y, b.z));
    }
    
    public Coord3f mul(float f) {
	return(of(x * f, y * f, z * f));
    }

    public Coord3f mul(float X, float Y, float Z) {
	return(of(x * X, y * Y, z * Z));
    }

    public Coord3f mul(Coord3f b) {
	return(mul(b.x, b.y, b.z));
    }

    public Coord3f div(float f) {
	return(of(x / f, y / f, z / f));
    }

    public Coord3f div(float X, float Y, float Z) {
	return(of(x / X, y / Y, z / Z));
    }

    public Coord3f div(Coord3f b) {
	return(div(b.x, b.y, b.z));
    }
    
    public Coord3f inv() {
	return(of(-x, -y, -z));
    }

    public Coord3f invy() {
	return(of(x, -y, z));
    }
    
    public float dmul(float X, float Y, float Z) {
	return(x * X + y * Y + z * Z);
    }
    
    public float dmul(Coord3f b) {
	return(dmul(b.x, b.y, b.z));
    }

    public Coord3f cmul(float X, float Y, float Z) {
	return(of(y * Z - z * Y, z * X - x * Z, x * Y - y * X));
    }

    public Coord3f cmul(Coord3f b) {
	return(cmul(b.x, b.y, b.z));
    }
    
    public Coord3f rot(Coord3f p, float a) {
	float c = (float)Math.cos(a), s = (float)Math.sin(a), C = 1.0f - c;
	float ax = p.x, ay = p.y, az = p.z;
	return(of((x * ((ax * ax * C) + c)) +
		  (y * ((ay * ax * C) - (az * s))) +
		  (z * ((az * ax * C) + (ay * s))),
		  (x * ((ax * ay * C) + (az * s))) +
		  (y * ((ay * ay * C) + c)) +
		  (z * ((az * ay * C) - (ax * s))),
		  (x * ((ax * az * C) - (ay * s))) +
		  (y * ((ay * az * C) + (ax * s))) +
		  (z * ((az * az * C) + c))));
    }

    public float abs() {
	return((float)Math.sqrt((x * x) + (y * y) + (z * z)));
    }

    public Coord3f norm() {
	float a = abs();
	if(a == 0.0)
	    return(of(0, 0, 0));
	return(div(a));
    }

    public float dist(Coord3f o) {
	float dx = o.x - x;
	float dy = o.y - y;
	float dz = o.z - z;
	return((float)Math.sqrt((dx * dx) + (dy * dy) + (dz * dz)));
    }
    
    public float xyangle(Coord3f o) {
	Coord3f c = o.sub(this);
	if(c.x == 0) {
	    if(c.y < 0)
		return((float)-PI / 2);
	    else
		return((float)PI / 2);
	} else {
	    if(c.x < 0) {
		if(c.y < 0)
		    return((float)(-PI + Math.atan(c.y / c.x)));
		else
		    return((float)(PI + Math.atan(c.y / c.x)));
	    } else {
		return((float)Math.atan(c.y / c.x));
	    }
	}
    }
    
    public float[] to3a() {
	return(new float[] {x, y, z});
    }
    
    public float[] to4a(float w) {
	return(new float[] {x, y, z, w});
    }
    
    public Coord round2() {
	return(new Coord((int)Math.round(x), (int)Math.round(y)));
    }
    
    public boolean isect(Coord c, Coord s) {
	return((x >= c.x) && (y >= c.y) && (x < c.x + s.x) && (y < c.y + s.y));
    }

    public String toString() {
	return(String.format("(%f, %f, %f)", x, y, z));
    }
}
