package integrations.mapv4;

import haven.*;
import haven.render.BaseColor;
import haven.render.BufPipe;
import haven.resutil.Ridges;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

import static haven.MCache.cmaps;

/**
 * @author APXEOLOG (Artyom Melnikov), at 28.01.2019
 */
public class MinimapImageGenerator {
    
    private static BufferedImage tileimg(int t, BufferedImage[] texes, MCache map) {
        BufferedImage img = texes[t];
        if (img == null) {
            Resource r = map.tilesetr(t);
            if (r == null)
                return (null);
            Resource.Image ir = r.layer(Resource.imgc);
            if (ir == null)
                return (null);
            img = ir.img;
            texes[t] = img;
        }
        return (img);
    }
    
    private static Color olcol(MCache.OverlayInfo olid) {
        /* XXX? */
        Material mat = olid.mat();
        BufPipe st = new BufPipe();
        mat.states.apply(st);
        if(st.get(BaseColor.slot) != null) {
            FColor bc = st.get(BaseColor.slot).color;
            return(new Color(Math.round(bc.r * 255), Math.round(bc.g * 255),
                Math.round(bc.b * 255), 255));
        }
        return(null);
    }
    
    public static BufferedImage drawoverlay(MCache map, MCache.Grid grid) {
        WritableRaster buf = PUtils.imgraster(cmaps);
        MapFile.Grid g = MapFile.Grid.from(map, grid);
        for(MapFile.Overlay ol : g.ols) {
            MCache.ResOverlay olid = ol.olid.loadsaved().flayer(MCache.ResOverlay.class);
            if(!olid.tags().contains("realm"))
                continue;
            Color col = olcol(olid);
            if(col == null)
                continue;
            Coord c = new Coord();
            for(c.y = 0; c.y < cmaps.y; c.y++) {
                for(c.x = 0; c.x < cmaps.x; c.x++) {
                    if(ol.get(c)) {
                        buf.setSample(c.x, c.y, 0, ((col.getRed()   * col.getAlpha()) + (buf.getSample(c.x, c.y, 1) * (255 - col.getAlpha()))) / 255);
                        buf.setSample(c.x, c.y, 1, ((col.getGreen() * col.getAlpha()) + (buf.getSample(c.x, c.y, 1) * (255 - col.getAlpha()))) / 255);
                        buf.setSample(c.x, c.y, 2, ((col.getBlue()  * col.getAlpha()) + (buf.getSample(c.x, c.y, 2) * (255 - col.getAlpha()))) / 255);
                        buf.setSample(c.x, c.y, 3, Math.max(buf.getSample(c.x, c.y, 3), col.getAlpha()));
                    }
                }
            }
        }
        return(PUtils.rasterimg(buf));
    }
    
    public static BufferedImage drawmap(MCache map, MCache.Grid grid) {
        BufferedImage[] texes = new BufferedImage[256];
        BufferedImage buf = TexI.mkbuf(MCache.cmaps);
        Coord c = new Coord();
        for (c.y = 0; c.y < MCache.cmaps.y; c.y++) {
            for (c.x = 0; c.x < MCache.cmaps.x; c.x++) {
                BufferedImage tex = tileimg(grid.gettile(c), texes, map);
                int rgb = 0;
                if (tex != null)
                    rgb = tex.getRGB(Utils.floormod(c.x, tex.getWidth()),
                        Utils.floormod(c.y, tex.getHeight()));
                buf.setRGB(c.x, c.y, rgb);
            }
        }
        for (c.y = 1; c.y < MCache.cmaps.y - 1; c.y++) {
            for (c.x = 1; c.x < MCache.cmaps.x - 1; c.x++) {
                int t = grid.gettile(c);
                Tiler tl = map.tiler(t);
                if (tl instanceof Ridges.RidgeTile) {
                    if (Ridges.brokenp(map, grid, c)) {
                        for (int y = c.y - 1; y <= c.y + 1; y++) {
                            for (int x = c.x - 1; x <= c.x + 1; x++) {
                                Color cc = new Color(buf.getRGB(x, y));
                                buf.setRGB(x, y, Utils.blendcol(cc, Color.BLACK, ((x == c.x) && (y == c.y)) ? 1 : 0.1).getRGB());
                            }
                        }
                    }
                }
            }
        }
        for (c.y = 0; c.y < MCache.cmaps.y; c.y++) {
            for (c.x = 0; c.x < MCache.cmaps.x; c.x++) {
                try {
                    int t = grid.gettile(c);
                    Coord r = c.add(grid.ul);
                    if ((map.gettile(r.add(-1, 0)) > t) ||
                        (map.gettile(r.add(1, 0)) > t) ||
                        (map.gettile(r.add(0, -1)) > t) ||
                        (map.gettile(r.add(0, 1)) > t)) {
                        buf.setRGB(c.x, c.y, Color.BLACK.getRGB());
                    }
                } catch (Exception e) {
                }
            }
        }
        return buf;
    }
}