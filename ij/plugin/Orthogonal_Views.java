package ij.plugin;
import ij.*;
import ij.gui.*;
import ij.measure.*;
import ij.process.*;
import java.awt.*;
import java.awt.image.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
 
/**
 * This plugin projects dynamically orthogonal XZ and YZ views of a stack. 
 * The output images are calibrated, which allows measurements to be performed more easily. 
 * 
 * Many thanks to Jerome Mutterer for the code contributions and testing.
 * Thanks to Wayne Rasband for the code that properly handles the image magnification.
 * 		
 * @author Dimiter Prodanov
 */
public class Orthogonal_Views implements PlugIn, MouseListener, MouseMotionListener, KeyListener, ActionListener, 
	ImageListener, WindowListener, AdjustmentListener, MouseWheelListener, FocusListener, CommandListener, Runnable, ComponentListener {

	private ImageWindow win;
	private ImagePlus imp;
	private boolean rgb;
	private ImageStack imageStack;
	private boolean hyperstack;
	private int currentChannel, currentFrame, currentMode; 
	private ImageCanvas canvas;
	private static final int H_ROI=0, H_ZOOM=1;
	private static boolean sticky=true;
	private static int xzID, yzID;
	private static Orthogonal_Views instance;
	private ImagePlus xz_image, yz_image;
	/** ImageProcessors for the xz and yz images */
	private ImageProcessor fp1, fp2;
	private double ax, ay, az;
	private boolean rotateYZ = Prefs.rotateYZ;
	private boolean flipXZ = Prefs.flipXZ;
	
	private int xyX, xyY;
	private Calibration cal, cal_xz, cal_yz;
	private double magnification=1.0;
	private Color color = Roi.getColor();
	private double min, max;
	private boolean syncZoom = true;
	private Point crossLoc;
	private boolean firstTime = true;
	private static int previousID, previousX, previousY;
	private Rectangle startingSrcRect;
	private boolean done;
	private boolean initialized;
	private boolean sliceSet;
	private Thread thread;
	private volatile boolean needsUpdate = false;
	private double lastMag = -1.0;
	private Rectangle lastXySrc = new Rectangle(-1, -1, -1, -1);
	private int lastXyDstW = -1, lastXyDstH = -1;
	private int lastSlice = -1;
	private ImageStack lastImageStack = null;
	private int lastUpdateX = -1, lastUpdateY = -1;
	private int lastXyX = Integer.MIN_VALUE, lastXyY = Integer.MIN_VALUE;
	private int lastXyW = -1, lastXyH = -1;
	final static String CROSS = "|OV|";

	 
	public void run(String arg) {
		imp = IJ.getImage();
		boolean isStack = imp.getStackSize()>1;
		hyperstack = imp.isHyperStack();
		if ((hyperstack||imp.isComposite()) && imp.getNSlices()<=1)
			isStack = false;
		if (instance!=null) {
			if (imp==instance.imp) {
				instance.dispose();
				return;
			} else if (isStack) {
				instance.dispose();
				if (IJ.isMacro()) IJ.wait(1000);
			} else {
				ImageWindow win = instance.imp!=null?instance.imp.getWindow():null;
				if (win!=null) win.toFront();
				return;
			}
		}
		if (!isStack) {
			IJ.error("Orthogonal Views", "This command requires a stack, or a hyperstack with Z>1.");
			return;
		}
		yz_image = WindowManager.getImage(yzID);
		rgb = imp.getBitDepth()==24 || hyperstack;
		int yzBitDepth = hyperstack?24:imp.getBitDepth();
		if (yz_image==null || yz_image.getHeight()!=imp.getHeight() || yz_image.getBitDepth()!=yzBitDepth)
			yz_image = imp.createImagePlus();
		xz_image = WindowManager.getImage(xzID);
		if (xz_image==null || xz_image.getWidth()!=imp.getWidth() || xz_image.getBitDepth()!=yzBitDepth)
			xz_image = imp.createImagePlus();
		instance = this;
		int mode = imp.getCompositeMode();
		ImageProcessor ip = mode==IJ.COMPOSITE?new ColorProcessor(imp.getImage()):imp.getProcessor();
		min = ip.getMin();
		max = ip.getMax();
		cal=this.imp.getCalibration();
		cal_xz = cal.copy();
		cal_yz = cal.copy();
		double calx=cal.pixelWidth;
		double caly=cal.pixelHeight;
		double calz=cal.pixelDepth;
		ax = 1.0;
		ay = caly/calx;
		az = calz/calx;
		if (az>100) {
			IJ.error("Z spacing ("+(int)az+") is too large.");
			return;
		}
		win = imp.getWindow();
		canvas = win.getCanvas();
		addListeners(canvas);
		magnification= canvas.getMagnification();
		imp.deleteRoi();
		Rectangle r = canvas.getSrcRect();
		if (imp.getID()==previousID)
			crossLoc = new Point(previousX, previousY);
		else
			crossLoc = new Point(r.x+r.width/2, r.y+r.height/2);
		imageStack = getStack();
		calibrate();
		if (createProcessors(imageStack)) {
			if (ip.isColorLut() || ip.isInvertedLut()) {
				ColorModel cm = ip.getColorModel();
				fp1.setColorModel(cm);
				fp2.setColorModel(cm);				
			}
			lastSlice = imp.getSlice();
			thread = new Thread(this, "Orthogonal Views");
			thread.start();
			IJ.wait(100);
			update();
		} else
			dispose();
	}
	
	private ImageStack getStack() {
		if (imp.isHyperStack()) {
			int slices = imp.getNSlices();
			int c=imp.getChannel();
			int z=imp.getSlice();
			int t=imp.getFrame();
			int mode = imp.getCompositeMode();
			rgb = mode==IJ.COMPOSITE;
			ColorModel cm = rgb?null:imp.getProcessor().getColorModel();
			if (cm!=null && fp1!=null && fp1.getBitDepth()!=24) {
				fp1.setColorModel(cm);
				fp2.setColorModel(cm);
			}
			ImageStack stack = imp.getStack();
			ImageStack stack2 = new ImageStack(imp.getWidth(), imp.getHeight());
			for (int i=1; i<=slices; i++) {
				if (rgb) {
					imp.setPositionWithoutUpdate(c, i, t);
					stack2.addSlice(null, new ColorProcessor(imp.getImage()));
				} else {
					int index = imp.getStackIndex(c, i, t);
					stack2.addSlice(null, stack.getProcessor(index));
				}
			}
			if (rgb)
				imp.setPosition(c, z, t);
			currentChannel = c;
			currentFrame = t;
			currentMode = mode;
			return stack2;
		} else
			return imp.getStack();
	}
 
	private void addListeners(ImageCanvas canvas) {
		canvas.addMouseListener(this);
		canvas.addMouseMotionListener(this);
		canvas.addKeyListener(this);
		win.addWindowListener (this);  
		win.addMouseWheelListener(this);
		win.addFocusListener(this);
		win.addComponentListener(this);
		if (win != null) {
			Component[] comps = win.getComponents();
			for (int i=0; i<comps.length; i++) {
				Component c = comps[i];
				if (c instanceof Adjustable)
					((Adjustable)c).addAdjustmentListener(this);
				else if (c instanceof ScrollbarWithLabel)
					((ScrollbarWithLabel)c).addAdjustmentListener(this);
			}
		}
		ImagePlus.addImageListener(this);
		Executer.addCommandListener(this);
	}
	 
	private void calibrate() {
		String xunit = cal.getXUnit();
		String yunit = cal.getYUnit();
		String zunit = cal.getZUnit();
		double o_depth=cal.pixelDepth;
		double o_height=cal.pixelHeight;
		double o_width=cal.pixelWidth;
		cal_yz.setXUnit(zunit);
		cal_yz.setYUnit(yunit);
		cal_yz.setZUnit(xunit);
		if (rotateYZ) {
			cal_yz.pixelHeight=o_depth/az;
			cal_yz.pixelWidth=o_height;
			cal_yz.setXUnit(yunit);
			cal_yz.setYUnit(zunit);
		} else {
			cal_yz.pixelWidth=o_depth/az;
			cal_yz.pixelHeight=o_height;
		}
		if (flipXZ)
			cal_yz.setInvertY(true);
		yz_image.setCalibration(cal_yz);
		yz_image.setIJMenuBar(false);
		cal_xz.setXUnit(xunit);
		cal_xz.setYUnit(zunit);
		cal_xz.setZUnit(yunit);
		cal_xz.pixelWidth=o_width;
		cal_xz.pixelHeight=o_depth/az;
		if (flipXZ)
			cal_xz.setInvertY(true);
		xz_image.setCalibration(cal_xz);
		xz_image.setIJMenuBar(false);
	}

	private void syncSideViewsZoomAndBounds() {
		if (imp == null || xz_image == null || yz_image == null) return;
		ImageWindow xyWin = imp.getWindow();
		ImageWindow xzWin = xz_image.getWindow();
		ImageWindow yzWin = yz_image.getWindow();
		if (xyWin == null || xzWin == null || yzWin == null) return;
		ImageCanvas xyIc = xyWin.getCanvas();
		ImageCanvas xzIc = xzWin.getCanvas();
		ImageCanvas yzIc = yzWin.getCanvas();
		if (xyIc == null || xzIc == null || yzIc == null) return;
		if (fp1 == null || fp2 == null) return;

		double mag = xyIc.getMagnification();
		Rectangle xySrc = xyIc.getSrcRect();
		if (xySrc == null) return;
		int z = imp.getSlice() - 1;

		int xyW = xyIc.getWidth();
		int xyH = xyIc.getHeight();

		if (mag == lastMag && xyW == lastXyDstW && xyH == lastXyDstH && xySrc.equals(lastXySrc)) {
			return;
		}
		lastMag = mag;
		lastXySrc.setBounds(xySrc);
		lastXyDstW = xyW;
		lastXyDstH = xyH;

		double arat = az / ax;
		double brat = az / ay;
		int zcoord = (int) Math.round(arat * z);
		if (flipXZ)
			zcoord = (int) Math.round(arat * (imp.getNSlices() - z));

		Rectangle maxBounds = GUI.getMaxWindowBounds(xyWin);

		// 1. Sync XZ (Bottom View)
		int xzImgWidth = fp1.getWidth();
		int xzImgHeight = fp1.getHeight();
		int xzDstWidth = xyW;

		Insets xzInsets = xzWin.getInsets();
		int xzInsetV = xzInsets != null ? (xzInsets.top + xzInsets.bottom) : 39;
		int maxAvailableHeight = maxBounds.y + maxBounds.height - (xyWin.getY() + xyWin.getHeight()) - xzInsetV - 15;
		int xzTargetHeight = (int) Math.round(xzImgHeight * mag);
		int xzDstHeight = Math.max(80, Math.min(xzTargetHeight, maxAvailableHeight));

		Rectangle curXzSrc = xzIc.getSrcRect();
		Rectangle xzSrc = new Rectangle();
		xzSrc.x = xySrc.x;
		xzSrc.width = xySrc.width;
		if (xzDstHeight >= xzTargetHeight) {
			xzSrc.y = 0;
			xzSrc.height = xzImgHeight;
			xzDstHeight = xzTargetHeight;
		} else {
			xzSrc.height = (int) Math.round(xzDstHeight / mag);
			if (xzSrc.height < 1) xzSrc.height = 1;
			if (xzSrc.height > xzImgHeight) xzSrc.height = xzImgHeight;
			int curY = (curXzSrc != null && curXzSrc.height == xzSrc.height) ? curXzSrc.y : (zcoord - xzSrc.height / 2);
			if (zcoord < curY || zcoord >= curY + xzSrc.height) {
				curY = zcoord - xzSrc.height / 2;
			}
			if (curY < 0) curY = 0;
			if (curY + xzSrc.height > xzImgHeight) curY = xzImgHeight - xzSrc.height;
			xzSrc.y = curY;
		}

		boolean xzSrcChanged = curXzSrc == null || !curXzSrc.equals(xzSrc) || xzIc.getMagnification() != mag;
		boolean xzSizeChanged = (xzIc.getWidth() != xzDstWidth || xzIc.getHeight() != xzDstHeight);
		if (xzSizeChanged) {
			xzIc.setSize(xzDstWidth, xzDstHeight);
		}
		if (xzSrcChanged) {
			xzIc.setSourceRect(xzSrc);
			xzIc.setMagnification(mag);
		}
		if (xzSizeChanged) {
			xzWin.pack();
		}

		// 2. Sync YZ (Right View)
		int yzImgWidth = fp2.getWidth();
		int yzImgHeight = fp2.getHeight();
		Insets yzInsets = yzWin.getInsets();

		if (!rotateYZ) {
			int yzDstHeight = xyH;
			int yzInsetH = yzInsets != null ? (yzInsets.left + yzInsets.right) : 16;
			int maxAvailableWidth = maxBounds.x + maxBounds.width - (xyWin.getX() + xyWin.getWidth()) - yzInsetH - 15;
			int yzTargetWidth = (int) Math.round(yzImgWidth * mag);
			int yzDstWidth = Math.max(80, Math.min(yzTargetWidth, maxAvailableWidth));

			Rectangle curYzSrc = yzIc.getSrcRect();
			Rectangle yzSrc = new Rectangle();
			yzSrc.y = xySrc.y;
			yzSrc.height = xySrc.height;
			if (yzDstWidth >= yzTargetWidth) {
				yzSrc.x = 0;
				yzSrc.width = yzImgWidth;
				yzDstWidth = yzTargetWidth;
			} else {
				yzSrc.width = (int) Math.round(yzDstWidth / mag);
				if (yzSrc.width < 1) yzSrc.width = 1;
				if (yzSrc.width > yzImgWidth) yzSrc.width = yzImgWidth;
				int curX = (curYzSrc != null && curYzSrc.width == yzSrc.width) ? curYzSrc.x : (zcoord - yzSrc.width / 2);
				if (zcoord < curX || zcoord >= curX + yzSrc.width) {
					curX = zcoord - yzSrc.width / 2;
				}
				if (curX < 0) curX = 0;
				if (curX + yzSrc.width > yzImgWidth) curX = yzImgWidth - yzSrc.width;
				yzSrc.x = curX;
			}

			boolean yzSrcChanged = curYzSrc == null || !curYzSrc.equals(yzSrc) || yzIc.getMagnification() != mag;
			boolean yzSizeChanged = (yzIc.getWidth() != yzDstWidth || yzIc.getHeight() != yzDstHeight);
			if (yzSizeChanged) {
				yzIc.setSize(yzDstWidth, yzDstHeight);
			}
			if (yzSrcChanged) {
				yzIc.setSourceRect(yzSrc);
				yzIc.setMagnification(mag);
			}
			if (yzSizeChanged) {
				yzWin.pack();
			}
		} else {
			int yzDstWidth = xyW;
			int yzInsetV = yzInsets != null ? (yzInsets.top + yzInsets.bottom) : 39;
			int maxAvailableHeightYZ = maxBounds.y + maxBounds.height - (xyWin.getY() + xyWin.getHeight()) - yzInsetV - 15;
			int yzTargetHeight = (int) Math.round(yzImgHeight * mag);
			int yzDstHeight = Math.max(80, Math.min(yzTargetHeight, maxAvailableHeightYZ));

			Rectangle curYzSrc = yzIc.getSrcRect();
			Rectangle yzSrc = new Rectangle();
			yzSrc.x = xySrc.y;
			yzSrc.width = xySrc.height;
			if (yzDstHeight >= yzTargetHeight) {
				yzSrc.y = 0;
				yzSrc.height = yzImgHeight;
				yzDstHeight = yzTargetHeight;
			} else {
				yzSrc.height = (int) Math.round(yzDstHeight / mag);
				if (yzSrc.height < 1) yzSrc.height = 1;
				if (yzSrc.height > yzImgHeight) yzSrc.height = yzImgHeight;
				int curY = (curYzSrc != null && curYzSrc.height == yzSrc.height) ? curYzSrc.y : (zcoord - yzSrc.height / 2);
				if (zcoord < curY || zcoord >= curY + yzSrc.height) {
					curY = zcoord - yzSrc.height / 2;
				}
				if (curY < 0) curY = 0;
				if (curY + yzSrc.height > yzImgHeight) curY = yzImgHeight - yzSrc.height;
				yzSrc.y = curY;
			}

			boolean yzSrcChanged = curYzSrc == null || !curYzSrc.equals(yzSrc) || yzIc.getMagnification() != mag;
			boolean yzSizeChanged = (yzIc.getWidth() != yzDstWidth || yzIc.getHeight() != yzDstHeight);
			if (yzSizeChanged) {
				yzIc.setSize(yzDstWidth, yzDstHeight);
			}
			if (yzSrcChanged) {
				yzIc.setSourceRect(yzSrc);
				yzIc.setMagnification(mag);
			}
			if (yzSizeChanged) {
				yzWin.pack();
			}
		}
	}

	private void updateMagnification(int x, int y) {
		syncSideViewsZoomAndBounds();
	}
	
	void updateViews(Point p, ImageStack is) {
		if (fp1==null) return;

		boolean needUpdateXZ = (p.y != lastUpdateY || xz_image.getWindow() == null);
		boolean needUpdateYZ = (p.x != lastUpdateX || yz_image.getWindow() == null);

		if (needUpdateXZ) {
			updateXZView(p, is);
			int width2 = fp1.getWidth();
			int height2 = (int) Math.round(fp1.getHeight() * az);
			if (height2 < 1) height2 = 1;
			ImageProcessor targetIp;
			if (width2 != fp1.getWidth() || height2 != fp1.getHeight()) {
				fp1.setInterpolate(true);
				targetIp = fp1.resize(width2, height2);
			} else {
				targetIp = fp1;
			}
			if (!rgb) targetIp.setMinAndMax(min, max);

			if (xz_image.getWindow() == null) {
				xz_image.setProcessor("XZ", targetIp);
			} else {
				ImageProcessor cur = xz_image.getProcessor();
				if (cur != targetIp) {
					if (cur != null && cur.getWidth() == targetIp.getWidth() && cur.getHeight() == targetIp.getHeight()) {
						cur.setPixels(targetIp.getPixels());
						if (!rgb) cur.setMinAndMax(min, max);
					} else {
						xz_image.setProcessor("XZ", targetIp);
					}
				}
				ImageCanvas ic = xz_image.getCanvas();
				if (ic != null) ic.setImageUpdated();
			}
			lastUpdateY = p.y;
		}

		if (needUpdateYZ) {
			if (rotateYZ)
				updateYZView(p, is);
			else
				updateZYView(p, is);

			int width2 = (int) Math.round(fp2.getWidth() * az);
			if (width2 < 1) width2 = 1;
			int height2 = fp2.getHeight();
			String title = "YZ";
			if (rotateYZ) {
				width2 = fp2.getWidth();
				height2 = (int) Math.round(fp2.getHeight() * az);
				if (height2 < 1) height2 = 1;
				title = "ZY";
			}
			ImageProcessor targetIp;
			if (width2 != fp2.getWidth() || height2 != fp2.getHeight()) {
				fp2.setInterpolate(true);
				targetIp = fp2.resize(width2, height2);
			} else {
				targetIp = fp2;
			}
			if (!rgb) targetIp.setMinAndMax(min, max);

			if (yz_image.getWindow() == null) {
				yz_image.setProcessor(title, targetIp);
			} else {
				ImageProcessor cur = yz_image.getProcessor();
				if (cur != targetIp) {
					if (cur != null && cur.getWidth() == targetIp.getWidth() && cur.getHeight() == targetIp.getHeight()) {
						cur.setPixels(targetIp.getPixels());
						if (!rgb) cur.setMinAndMax(min, max);
					} else {
						yz_image.setProcessor(title, targetIp);
					}
				}
				ImageCanvas ic = yz_image.getCanvas();
				if (ic != null) ic.setImageUpdated();
			}
			lastUpdateX = p.x;
		}

		if (yz_image.getWindow() == null) {
			calibrate();
			yz_image.show();
			ImageWindow yzWin = yz_image.getWindow();
			if (yzWin != null) {
				yzWin.removeMouseWheelListener(yzWin);
				yzWin.addMouseWheelListener(this);
				yzWin.addWindowListener(this);
			}
			ImageCanvas ic = yz_image.getCanvas();
			ic.addKeyListener(this);
			ic.addMouseListener(this);
			ic.addMouseMotionListener(this);
			ic.addMouseWheelListener(this);
			ic.setCustomRoi(true);
			yzID = yz_image.getID();
		} else {
			ImageCanvas ic = yz_image.getWindow().getCanvas();
			ic.setCustomRoi(true);
		}
		if (xz_image.getWindow() == null) {
			calibrate();
			xz_image.show();
			ImageWindow xzWin = xz_image.getWindow();
			if (xzWin != null) {
				xzWin.removeMouseWheelListener(xzWin);
				xzWin.addMouseWheelListener(this);
				xzWin.addWindowListener(this);
			}
			ImageCanvas ic = xz_image.getCanvas();
			ic.addKeyListener(this);
			ic.addMouseListener(this);
			ic.addMouseMotionListener(this);
			ic.addMouseWheelListener(this);
			ic.setCustomRoi(true);
			xzID = xz_image.getID();
		} else {
			ImageCanvas ic = xz_image.getWindow().getCanvas();
			ic.setCustomRoi(true);
		}
	}

	void arrangeWindows(boolean sticky) {
		ImageWindow xyWin = imp != null ? imp.getWindow() : null;
		if (xyWin == null) return;
		ImageWindow yzWin = yz_image != null ? yz_image.getWindow() : null;
		ImageWindow xzWin = xz_image != null ? xz_image.getWindow() : null;
		if (yzWin == null || xzWin == null) return;

		Point loc = xyWin.getLocation();
		int curW = xyWin.getWidth();
		int curH = xyWin.getHeight();

		Insets xyInsets = xyWin.getInsets();
		Insets yzInsets = yzWin.getInsets();

		int xGap = IJ.isWindows() ? ((xyInsets != null ? xyInsets.right : 8) + (yzInsets != null ? yzInsets.left : 8)) : 0;
		int yGap = IJ.isWindows() ? (xyInsets != null ? xyInsets.bottom : 8) : 0;

		int yzX = loc.x + curW - xGap;
		int yzY = loc.y;
		int xzX = loc.x;
		int xzY = loc.y + curH - yGap;

		Point curYz = yzWin.getLocation();
		Point curXz = xzWin.getLocation();

		if (lastXyX == loc.x && lastXyY == loc.y && lastXyW == curW && lastXyH == curH
				&& curYz.x == yzX && curYz.y == yzY && curXz.x == xzX && curXz.y == xzY && !firstTime) {
			return;
		}

		if (curYz.x != yzX || curYz.y != yzY) {
			yzWin.setLocation(yzX, yzY);
		}

		if (curXz.x != xzX || curXz.y != xzY) {
			xzWin.setLocation(xzX, xzY);
		}

		lastXyX = loc.x;
		lastXyY = loc.y;
		lastXyW = curW;
		lastXyH = curH;

		if (firstTime) {
			xyWin.toFront();
			if (!sliceSet && imp.getSlice() == 1) {
				if (hyperstack)
					imp.setPosition(imp.getChannel(), imp.getNSlices() / 2, imp.getFrame());
				else
					imp.setSlice(imp.getNSlices() / 2);
			}
			firstTime = false;
		}
	}
	
	/**
	 * @param is - used to get the dimensions of the new ImageProcessors
	 * @return
	 */
	boolean createProcessors(ImageStack is) {
		ImageProcessor ip=is.getProcessor(1);
		int width= is.getWidth();
		int height=is.getHeight();
		int ds=is.getSize(); 
		double arat=1.0;//az/ax;
		double brat=1.0;//az/ay;
		int za=(int)(ds*arat);
		int zb=(int)(ds*brat);
		
		if (ip instanceof FloatProcessor) {
			fp1=new FloatProcessor(width,za);
			if (rotateYZ)
				fp2=new FloatProcessor(height,zb);
			else
				fp2=new FloatProcessor(zb,height);
			return true;
		}
		
		if (ip instanceof ByteProcessor) {
			fp1=new ByteProcessor(width,za);
			if (rotateYZ)
				fp2=new ByteProcessor(height,zb);
			else
				fp2=new ByteProcessor(zb,height);
			return true;
		}
		
		if (ip instanceof ShortProcessor) {
			fp1=new ShortProcessor(width,za);
			if (rotateYZ)
				fp2=new ShortProcessor(height,zb);
			else
				fp2=new ShortProcessor(zb,height);
			return true;
		}
		
		if (ip instanceof ColorProcessor) {
			fp1=new ColorProcessor(width,za);
			if (rotateYZ)
				fp2=new ColorProcessor(height,zb);
			else
				fp2=new ColorProcessor(zb,height);
			return true;
		}
		
		return false;
	}
	
	void updateXZView(Point p, ImageStack is) {
		int width= is.getWidth();
		int size=is.getSize();
		ImageProcessor ip=is.getProcessor(1);
		
		int y=p.y;
		// XZ
		if (ip instanceof ShortProcessor) {
			short[] newpix = (short[]) fp1.getPixels();
			if (newpix == null || newpix.length != width*size) {
				newpix = new short[width*size];
				fp1.setPixels(newpix);
			}
			for (int i=0; i<size; i++) { 
				Object pixels=is.getPixels(i+1);
				if (flipXZ)
					System.arraycopy(pixels, width*y, newpix, width*(size-i-1), width);
				else
					System.arraycopy(pixels, width*y, newpix, width*i, width);
			}
			return;
		}
		
		if (ip instanceof ByteProcessor) {
			byte[] newpix = (byte[]) fp1.getPixels();
			if (newpix == null || newpix.length != width*size) {
				newpix = new byte[width*size];
				fp1.setPixels(newpix);
			}
			for (int i=0;i<size; i++) { 
				Object pixels=is.getPixels(i+1);
				if (flipXZ)
					System.arraycopy(pixels, width*y, newpix, width*(size-i-1), width);
				else
					System.arraycopy(pixels, width*y, newpix, width*i, width);
			}
			return;
		}
		
		if (ip instanceof FloatProcessor) {
			float[] newpix = (float[]) fp1.getPixels();
			if (newpix == null || newpix.length != width*size) {
				newpix = new float[width*size];
				fp1.setPixels(newpix);
			}
			for (int i=0; i<size; i++) { 
				Object pixels=is.getPixels(i+1);
				if (flipXZ)
					System.arraycopy(pixels, width*y, newpix, width*(size-i-1), width);
				else
					System.arraycopy(pixels, width*y, newpix, width*i, width);
			}
			return;
		}
		
		if (ip instanceof ColorProcessor) {
			int[] newpix = (int[]) fp1.getPixels();
			if (newpix == null || newpix.length != width*size) {
				newpix = new int[width*size];
				fp1.setPixels(newpix);
			}
			for (int i=0;i<size; i++) { 
				Object pixels=is.getPixels(i+1);
				if (flipXZ)
					System.arraycopy(pixels, width*y, newpix, width*(size-i-1), width);
				else
					System.arraycopy(pixels, width*y, newpix, width*i, width);
			}
			return;
		}
		
	}
	
	void updateYZView(Point p, ImageStack is) {
		int width= is.getWidth();
		int height=is.getHeight();
		int ds=is.getSize();
		ImageProcessor ip=is.getProcessor(1);
		int x=p.x;
		
		if (ip instanceof FloatProcessor) {
			float[] newpix = (float[]) fp2.getPixels();
			if (newpix == null || newpix.length != ds*height) {
				newpix = new float[ds*height];
				fp2.setPixels(newpix);
			}
			for (int i=0;i<ds; i++) { 
				float[] pixels= (float[]) is.getPixels(i+1);
				for (int j=0;j<height;j++)
					newpix[(ds-i-1)*height + j] = pixels[x + j* width];
			}
		}
		
		if (ip instanceof ByteProcessor) {
			byte[] newpix = (byte[]) fp2.getPixels();
			if (newpix == null || newpix.length != ds*height) {
				newpix = new byte[ds*height];
				fp2.setPixels(newpix);
			}
			for (int i=0;i<ds; i++) { 
				byte[] pixels= (byte[]) is.getPixels(i+1);
				for (int j=0;j<height;j++)
					newpix[(ds-i-1)*height + j] = pixels[x + j* width];
			}
		}
		
		if (ip instanceof ShortProcessor) {
			short[] newpix = (short[]) fp2.getPixels();
			if (newpix == null || newpix.length != ds*height) {
				newpix = new short[ds*height];
				fp2.setPixels(newpix);
			}
			for (int i=0;i<ds; i++) { 
				short[] pixels= (short[]) is.getPixels(i+1);
				for (int j=0;j<height;j++)
					newpix[(ds-i-1)*height + j] = pixels[x + j* width];
			}
		}
		
		if (ip instanceof ColorProcessor) {
			int[] newpix = (int[]) fp2.getPixels();
			if (newpix == null || newpix.length != ds*height) {
				newpix = new int[ds*height];
				fp2.setPixels(newpix);
			}
			for (int i=0;i<ds; i++) { 
				int[] pixels= (int[]) is.getPixels(i+1);
				for (int j=0;j<height;j++)
					newpix[(ds-i-1)*height + j] = pixels[x + j* width];
			}
		}
		if (!flipXZ)
			fp2.flipVertical();
		
	}
	
	void updateZYView(Point p, ImageStack is) {
		int width= is.getWidth();
		int height=is.getHeight();
		int ds=is.getSize();
		ImageProcessor ip=is.getProcessor(1);
		int x=p.x;
		
		if (ip instanceof FloatProcessor) {
			float[] newpix = (float[]) fp2.getPixels();
			if (newpix == null || newpix.length != ds*height) {
				newpix = new float[ds*height];
				fp2.setPixels(newpix);
			}
			for (int i=0;i<ds; i++) { 
				float[] pixels= (float[]) is.getPixels(i+1);
				for (int y=0;y<height;y++)
					newpix[i + y*ds] = pixels[x + y* width];
			}
		}
		
		if (ip instanceof ByteProcessor) {
			byte[] newpix = (byte[]) fp2.getPixels();
			if (newpix == null || newpix.length != ds*height) {
				newpix = new byte[ds*height];
				fp2.setPixels(newpix);
			}
			for (int i=0;i<ds; i++) { 
				byte[] pixels= (byte[]) is.getPixels(i+1);
				for (int y=0;y<height;y++)
					newpix[i + y*ds] = pixels[x + y* width];
			}
		}
		
		if (ip instanceof ShortProcessor) {
			short[] newpix = (short[]) fp2.getPixels();
			if (newpix == null || newpix.length != ds*height) {
				newpix = new short[ds*height];
				fp2.setPixels(newpix);
			}
			for (int i=0;i<ds; i++) { 
				short[] pixels= (short[]) is.getPixels(i+1);
				for (int y=0;y<height;y++)
					newpix[i + y*ds] = pixels[x + y* width];
			}
		}
		
		if (ip instanceof ColorProcessor) {
			int[] newpix = (int[]) fp2.getPixels();
			if (newpix == null || newpix.length != ds*height) {
				newpix = new int[ds*height];
				fp2.setPixels(newpix);
			}
			for (int i=0;i<ds; i++) { 
				int[] pixels= (int[]) is.getPixels(i+1);
				for (int y=0;y<height;y++)
					newpix[i + y*ds] = pixels[x + y* width];
			}
		}
		
	}
	 
	/** draws the crosses on the images */
	void drawCross(ImagePlus imp, Point p, GeneralPath path) {
		int width=imp.getWidth();
		int height=imp.getHeight();
		float x = p.x;
		float y = p.y;
		path.moveTo(0f, y);
		path.lineTo(width, y);
		path.moveTo(x, 0f);
		path.lineTo(x, height);	
	}
	
	void dispose() {
		synchronized(this) {
			done = true;
			notify();
		}
		Overlay overlay = imp.getOverlay();
		if (overlay!=null) {
			overlay.remove(CROSS);
			ImageCanvas ic = imp.getCanvas();
			if (ic!=null)
				ic.setCustomRoi(true);
			if (overlay.size()==0)
				imp.setOverlay(null);
			else
				imp.draw();
		}
		if (canvas!=null) {
			canvas.removeMouseListener(this);
			canvas.removeMouseMotionListener(this);
			canvas.removeKeyListener(this);
			canvas.setCustomRoi(false);
		}
		if (xz_image != null) {
			xz_image.setOverlay(null);
			ImageWindow win1 = xz_image.getWindow();
			if (win1!=null) {
				win1.removeWindowListener(this);
				win1.removeMouseWheelListener(this);
				ImageCanvas ic = win1.getCanvas();
				if (ic!=null) {
					ic.removeKeyListener(this);
					ic.removeMouseListener(this);
					ic.removeMouseMotionListener(this);
					ic.removeMouseWheelListener(this);
					ic.setCustomRoi(false);
				}
			}
			xz_image.changes = false;
			xz_image.close();
		}
		if (yz_image != null) {
			yz_image.setOverlay(null);
			ImageWindow win2 = yz_image.getWindow();
			if (win2!=null) {
				win2.removeWindowListener(this);
				win2.removeMouseWheelListener(this);
				ImageCanvas ic = win2.getCanvas();
				if (ic!=null) {
					ic.removeKeyListener(this);
					ic.removeMouseListener(this);
					ic.removeMouseMotionListener(this);
					ic.removeMouseWheelListener(this);
					ic.setCustomRoi(false);
				}
			}
			yz_image.changes = false;
			yz_image.close();
		}
		ImagePlus.removeImageListener(this);
		Executer.removeCommandListener(this);
		if (win != null) {
			Component[] comps = win.getComponents();
			for (int i=0; i<comps.length; i++) {
				Component c = comps[i];
				if (c instanceof Adjustable)
					((Adjustable)c).removeAdjustmentListener(this);
				else if (c instanceof ScrollbarWithLabel)
					((ScrollbarWithLabel)c).removeAdjustmentListener(this);
			}
			win.removeComponentListener(this);
			win.removeWindowListener(this);
			win.removeMouseWheelListener(this);
			win.removeFocusListener(this);
			win.setResizable(true);
		}
		instance = null;
		previousID = imp.getID();
		synchronized(this) {
			previousX = crossLoc.x;
			previousY = crossLoc.y;
		}
		lastMag = -1.0;
		lastXySrc.setBounds(-1, -1, -1, -1);
		lastXyDstW = -1;
		lastXyDstH = -1;
		lastSlice = -1;
		lastImageStack = null;
		lastUpdateX = -1;
		lastUpdateY = -1;
		lastXyX = Integer.MIN_VALUE;
		lastXyY = Integer.MIN_VALUE;
		lastXyW = -1;
		lastXyH = -1;
		imageStack = null;
	}
	
	public void mouseClicked(MouseEvent e) {
	}

	public void mouseEntered(MouseEvent e) {
	}

	public void mouseExited(MouseEvent e) {
	}

	public void mousePressed(MouseEvent e) {
		ImageCanvas xyCanvas = imp.getCanvas();
		startingSrcRect = (Rectangle)xyCanvas.getSrcRect().clone();
		mouseDragged(e);
	}

	public void mouseDragged(MouseEvent e) {
		if (IJ.spaceBarDown())  // scrolling?
			return;
		synchronized(this) {
			if (e.getSource().equals(canvas)) {
				crossLoc = canvas.getCursorLoc();
			} else if (e.getSource().equals(xz_image.getCanvas())) {
				crossLoc.x = xz_image.getCanvas().getCursorLoc().x;
				int pos = xz_image.getCanvas().getCursorLoc().y;
				int z = (int)Math.round(pos/az);
				int slice = flipXZ?imp.getNSlices()-z:z+1;
				if (slice != imp.getSlice()) {
					if (hyperstack) {
						imp.setPositionWithoutUpdate(imp.getChannel(), slice, imp.getFrame());
						imp.updateAndDraw();
					} else {
						imp.setSliceWithoutUpdate(slice);
						imp.updateAndDraw();
					}
				}
			} else if (e.getSource().equals(yz_image.getCanvas())) {
				int pos;
				if (rotateYZ) {
					crossLoc.y = yz_image.getCanvas().getCursorLoc().x;
					pos = yz_image.getCanvas().getCursorLoc().y;
				} else {
					crossLoc.y = yz_image.getCanvas().getCursorLoc().y;
					pos = yz_image.getCanvas().getCursorLoc().x;
				}
				int z = (int)Math.round(pos/az);
				int slice = flipXZ?imp.getNSlices()-z:z+1;
				if (slice != imp.getSlice()) {
					if (hyperstack) {
						imp.setPositionWithoutUpdate(imp.getChannel(), slice, imp.getFrame());
						imp.updateAndDraw();
					} else {
						imp.setSliceWithoutUpdate(slice);
						imp.updateAndDraw();
					}
				}
			}
		}
		update();
	}

	public void mouseReleased(MouseEvent e) {
		ImageCanvas ic = imp.getCanvas();
		Rectangle srcRect = ic.getSrcRect();
		if (srcRect.x!=startingSrcRect.x || srcRect.y!=startingSrcRect.y) {
			// user has scrolled xy image
			int dy = srcRect.y - startingSrcRect.y;
			ImageCanvas yzic = yz_image.getCanvas();
			Rectangle yzSrcRect =yzic.getSrcRect();
			if (rotateYZ) {
				yzSrcRect.x += dy;
				if (yzSrcRect.x<0)
					yzSrcRect.x = 0;
				if (yzSrcRect.x>yz_image.getWidth()-yzSrcRect.width)
					yzSrcRect.y = yz_image.getWidth()-yzSrcRect.width;
			} else {
				yzSrcRect.y += dy;
				if (yzSrcRect.y<0)
					yzSrcRect.y = 0;
				if (yzSrcRect.y>yz_image.getHeight()-yzSrcRect.height)
					yzSrcRect.y = yz_image.getHeight()-yzSrcRect.height;
			}
			yzic.repaint();
			int dx = srcRect.x - startingSrcRect.x;
			ImageCanvas xzic = xz_image.getCanvas();
			Rectangle xzSrcRect =xzic.getSrcRect();
			xzSrcRect.x += dx;
			if (xzSrcRect.x<0)
				xzSrcRect.x = 0;
			if (xzSrcRect.x>xz_image.getWidth()-xzSrcRect.width)
				xzSrcRect.x = xz_image.getWidth()-xzSrcRect.width;
			xzic.repaint();
		}
	}
	
	/** Refresh the output windows. */
	synchronized void update() {
		needsUpdate = true;
		notify();
	}
	
	private void exec() {
		if (canvas==null)
			return;
		int width=imp.getWidth();
		int height=imp.getHeight();
		boolean stackRebuilt = false;
		if (hyperstack) {
			int mode = IJ.COMPOSITE;
			if (imp.isComposite()) {
				mode = ((CompositeImage)imp).getMode();
				if (mode!=currentMode)
					imageStack = null;
			}
			if (imageStack!=null) {
				int c = imp.getChannel();
				int t = imp.getFrame();
				if ((mode!=IJ.COMPOSITE&&c!=currentChannel) || t!=currentFrame)
					imageStack = null;
			}
		}
		ImageStack is = imageStack;
		if (is==null) {
			is = imageStack = getStack();
			stackRebuilt = true;
		}
		if (is != lastImageStack || stackRebuilt) {
			lastImageStack = is;
			lastUpdateX = -1;
			lastUpdateY = -1;
		}
		double arat=az/ax;
		double brat=az/ay;
		Point p;
		synchronized(this) {
			p = new Point(crossLoc.x, crossLoc.y);
		}
		if (p.y>=height) p.y=height-1;
		if (p.x>=width) p.x=width-1;
		if (p.x<0) p.x=0;
		if (p.y<0) p.y=0;
		updateViews(p, is);
		GeneralPath path = new GeneralPath();
		drawCross(imp, p, path);
		if (!done) {
			if (imp.getOverlay()==null)
				imp.setOverlay(new Overlay());
			setOverlay(imp, path);
		}
		canvas.setCustomRoi(true);
		updateCrosses(p.x, p.y, arat, brat);
		if (syncZoom) updateMagnification(p.x, p.y);
		arrangeWindows(sticky);
		initialized = true;
	}

	private void setOverlay(ImagePlus imp, GeneralPath path) {
		Overlay overlay = imp.getOverlay();
		if (overlay==null)
			overlay = new Overlay();
		Roi roi = new ShapeRoi(path);
		roi.setStrokeColor(color);
		roi.setStroke(new BasicStroke(1));
		overlay.remove(CROSS);
		overlay.add(roi, CROSS);
		imp.setOverlay(overlay);
	}

	private void updateCrosses(int x, int y, double arat, double brat) {
		Point p;
		int z=imp.getNSlices();
		int zlice=imp.getSlice()-1;
		int zcoord=(int)Math.round(arat*zlice);
		if (flipXZ)
			zcoord = (int)Math.round(arat*(z-zlice));		
		ImageCanvas xzCanvas = xz_image.getCanvas();
		p=new Point (x, zcoord);
		GeneralPath path = new GeneralPath();
		drawCross(xz_image, p, path);
		if (!done)
			setOverlay(xz_image, path);
		if (rotateYZ) {
			if (flipXZ)
				zcoord=(int)Math.round(brat*(z-zlice));
			else
				zcoord=(int)Math.round(brat*(zlice));			
			p=new Point (y, zcoord);
		} else {
			zcoord=(int)Math.round(arat*zlice);
			p=new Point (zcoord, y);
		}
		path = new GeneralPath();
		drawCross(yz_image, p, path);
		if (!done)
			setOverlay(yz_image, path);
		IJ.showStatus(imp.getLocationAsString(x, y));
	}

	public void mouseMoved(MouseEvent e) {
	}

	public void keyPressed(KeyEvent e) {
		int key = e.getKeyCode();
		if (key==KeyEvent.VK_ESCAPE) {
			IJ.beep();
			dispose();
		} else if (IJ.shiftKeyDown()) {
			int width=imp.getWidth(), height=imp.getHeight();
			synchronized(this) {
				switch (key) {
					case KeyEvent.VK_LEFT: crossLoc.x--; if (crossLoc.x<0) crossLoc.x=0; break;
					case KeyEvent.VK_RIGHT: crossLoc.x++; if (crossLoc.x>=width) crossLoc.x=width-1; break;
					case KeyEvent.VK_UP: crossLoc.y--; if (crossLoc.y<0) crossLoc.y=0; break;
					case KeyEvent.VK_DOWN: crossLoc.y++; if (crossLoc.y>=height) crossLoc.y=height-1; break;
					default: return;
				}
			}
			update();
		}
	}

	public void keyReleased(KeyEvent e) {
	}

	public void keyTyped(KeyEvent e) {
	}

	public void actionPerformed(ActionEvent ev) {
	}

	public void imageClosed(ImagePlus imp) {
		if (!done)
			dispose();
	}

	public void imageOpened(ImagePlus imp) {
	}

	public void imageUpdated(ImagePlus imp) {
		if (imp==this.imp) {
			ImageProcessor ip = imp.getProcessor();
			double newMin = ip.getMin();
			double newMax = ip.getMax();
			int newSlice = imp.getSlice();
			if (newMin != min || newMax != max) {
				min = newMin;
				max = newMax;
				lastUpdateX = -1;
				lastUpdateY = -1;
				lastSlice = newSlice;
				update();
			} else if (newSlice != lastSlice) {
				lastSlice = newSlice;
				update();
			} else {
				// Pixel data or image state changed without slice/min/max change (e.g. paintbrush, fill, filter)
				lastUpdateX = -1;
				lastUpdateY = -1;
				update();
			}
		}
	}

	public String commandExecuting(String command) {
		if (command.equals("In")||command.equals("Out")) {
			ImagePlus cimp = WindowManager.getCurrentImage();
			if (cimp==null) return command;
			if (cimp==imp || cimp==xz_image || cimp==yz_image) {
				ImageCanvas ic = imp.getCanvas();
				if (ic==null) return null;
				int x, y;
				synchronized(this) {
					x = ic.screenX(crossLoc.x);
					y = ic.screenY(crossLoc.y);
				}
				if (command.equals("In")) {
					ic.zoomIn(x, y);
					if (ic.getMagnification()<=1.0) imp.repaintWindow();
				} else {
					ic.zoomOut(x, y);
					if (ic.getMagnification()<1.0) imp.repaintWindow();
				}
				syncSideViewsZoomAndBounds();
				arrangeWindows(sticky);
				update();
				return null;
			} else
				return command;
		} else if (command.equals("Flip Vertically")&& xz_image!=null) {
			if (xz_image==WindowManager.getCurrentImage()) {
				flipXZ = !flipXZ;
				update();
				return null;
			} else
				return command;
		} else
			return command;
	}

	public void windowActivated(WindowEvent e) {
		ImageWindow xyWin = imp!=null ? imp.getWindow() : null;
		ImageWindow yzWin = yz_image!=null ? yz_image.getWindow() : null;
		ImageWindow xzWin = xz_image!=null ? xz_image.getWindow() : null;
		if (xyWin==null || yzWin==null || xzWin==null) return;

		arrangeWindows(sticky);
	}

	public void windowClosed(WindowEvent e) {
	}

	public void windowClosing(WindowEvent e) {
		if (!done)
			dispose();		
	}

	public void windowDeactivated(WindowEvent e) {
	}

	public void windowDeiconified(WindowEvent e) {
		if (e.getSource() == win) {
			if (yz_image!=null && yz_image.getWindow()!=null)
				yz_image.getWindow().setState(Frame.NORMAL);
			if (xz_image!=null && xz_image.getWindow()!=null)
				xz_image.getWindow().setState(Frame.NORMAL);
		}
		arrangeWindows(sticky);
	}

	public void windowIconified(WindowEvent e) {
		if (e.getSource() == win) {
			if (yz_image!=null && yz_image.getWindow()!=null)
				yz_image.getWindow().setState(Frame.ICONIFIED);
			if (xz_image!=null && xz_image.getWindow()!=null)
				xz_image.getWindow().setState(Frame.ICONIFIED);
		}
	}

	public void windowOpened(WindowEvent e) {
	}

	public void adjustmentValueChanged(AdjustmentEvent e) {
		update();
	}
		
	public void mouseWheelMoved(MouseWheelEvent e) {
		boolean ctrl = (e.getModifiers() & Event.CTRL_MASK) != 0 || IJ.controlKeyDown();
		int rotation = e.getWheelRotation();
		if (rotation == 0) return;

		Object src = e.getSource();
		boolean fromMain = (src == win || (imp != null && src == imp.getCanvas()));
		boolean fromXZ = (xz_image != null && (src == xz_image.getWindow() || src == xz_image.getCanvas()));
		boolean fromYZ = (yz_image != null && (src == yz_image.getWindow() || src == yz_image.getCanvas()));

		if (ctrl || IJ.shiftKeyDown()) {
			ImageCanvas xyIc = imp != null ? imp.getCanvas() : null;
			if (xyIc != null) {
				if (!fromMain) {
					int x, y;
					synchronized(this) {
						x = xyIc.screenX(crossLoc.x);
						y = xyIc.screenY(crossLoc.y);
					}
					if (rotation < 0)
						xyIc.zoomIn(x, y);
					else
						xyIc.zoomOut(x, y);
					if (xyIc.getMagnification() <= 1.0)
						imp.repaintWindow();
				}
				syncSideViewsZoomAndBounds();
				arrangeWindows(sticky);
				update();
			}
			return;
		}

		synchronized(this) {
			if (fromXZ) {
				crossLoc.y += rotation;
			} else if (fromYZ) {
				crossLoc.x += rotation;
			}
		}
		update();
	}

	public void componentMoved(ComponentEvent e) {
		if (e.getSource() == win) {
			arrangeWindows(sticky);
		}
	}

	public void componentResized(ComponentEvent e) {
		if (e.getSource() == win) {
			syncSideViewsZoomAndBounds();
			arrangeWindows(sticky);
		}
	}

	public void componentShown(ComponentEvent e) {}
	public void componentHidden(ComponentEvent e) {}

	public void focusGained(FocusEvent e) {
		ImageCanvas ic = imp.getCanvas();
		if (ic!=null) canvas.requestFocus();
		arrangeWindows(sticky);
	}

	public void focusLost(FocusEvent e) {
		arrangeWindows(sticky);
	}
	
	public static ImagePlus getImage() {
		if (instance!=null)
			return instance.imp;
		else
			return null;
	}
	
	public static int getImageID() {
		ImagePlus img = getImage();
		return img!=null?img.getID():0;
	}

	/** Returns the IDs of the XY, YZ and XZ images as an int array.*/
	public static int[] getImageIDs() {
		int[] ids = new int[3];
		Orthogonal_Views instance2 = getInstance();
		if (instance2==null)
			return ids;
		ids[0] = instance2.imp.getID();
		ids[1] = instance2.yz_image.getID();
		ids[2] = instance2.xz_image.getID();
		return ids;
	}

 	public static void stop() {
		if (instance!=null)
			instance.dispose();
	}

 	public static void start() {
		if (instance==null)
			IJ.run("Orthogonal Views");
	}

	public static synchronized boolean isOrthoViewsImage(ImagePlus imp) {
		if (imp==null || instance==null)
			return false;
		else
			return imp==instance.imp || imp==instance.xz_image || imp==instance.yz_image;
	}

	public static Orthogonal_Views getInstance() {
		return instance;
	}

	public int[] getCrossLoc() {
		int[] loc = new int[3];
		synchronized(this) {
			loc[0] = crossLoc.x;
			loc[1] = crossLoc.y;
		}
		loc[2] = imp.getSlice()-1;
		return loc;
	}
	
	public void setCrossLoc(int x, int y, int z) {
		synchronized(this) {
			crossLoc.setLocation(x, y);
		}
		int slice = z+1;
		if (slice!=imp.getSlice()) {
			if (hyperstack) {
				imp.setPositionWithoutUpdate(imp.getChannel(), slice, imp.getFrame());
				imp.updateAndDraw();
			} else {
				imp.setSliceWithoutUpdate(slice);
				imp.updateAndDraw();
			}
			sliceSet = true;
		}
		while (!initialized) {
			IJ.wait(10);
		}
		update();
	}
	
	public ImagePlus getXZImage(){
		return xz_image;
	}
	
	public ImagePlus getYZImage(){
		return yz_image;
	}
	
	public void run() {
		while (!done) {
			synchronized(this) {
				while (!needsUpdate && !done) {
					try {wait();}
					catch(InterruptedException e) {}
				}
				needsUpdate = false;
			}
			if (!done)
				exec();
		}
	}

}
