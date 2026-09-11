package ij.plugin;

import java.awt.*;
import java.io.*;
import java.util.regex.*;
import ij.*;
import ij.io.*;
import ij.process.*;
import ij.measure.*;

/** This plugin implements the File/Import/Raw command and provides automatic opening for formatted raw volume files. */
public class Raw implements PlugIn {

	private static String defaultDirectory = null;

	public void run(String arg) {
		if (arg!=null && !arg.trim().isEmpty()) {
			ImagePlus imp = openAuto(arg);
			if (imp!=null) {
				imp.show();
				if (imp.getStackSize() > 1)
					IJ.run("Orthogonal Views");
				return;
			}
		}
		OpenDialog od = new OpenDialog("Open Raw...", arg);
		String directory = od.getDirectory();
		String fileName = od.getFileName();
		if (fileName==null)
			return;
		ImagePlus imp = openAuto(directory + fileName);
		if (imp!=null) {
			imp.show();
			if (imp.getStackSize() > 1)
				IJ.run("Orthogonal Views");
			return;
		}
		ImportDialog d = new ImportDialog(fileName, directory);
		d.openImage();
	}

	private static class RawCandidate {
		int w, h, d, type;
		long offset;
		RawCandidate(int w, int h, int d, int type, long offset) {
			this.w = w; this.h = h; this.d = d; this.type = type; this.offset = offset;
		}
	}

	private static RawCandidate match3dPattern(String text, long fileLength) {
		if (text == null || text.trim().isEmpty()) return null;
		Pattern p3d = Pattern.compile("(?<!\\d)(\\d{2,5})[-_xX](\\d{2,5})[-_xX](\\d{1,5})(?!\\d)");
		Matcher m3d = p3d.matcher(text);
		while (m3d.find()) {
			try {
				int w = Integer.parseInt(m3d.group(1));
				int h = Integer.parseInt(m3d.group(2));
				int d = Integer.parseInt(m3d.group(3));
				if (w<=0 || h<=0 || d<=0) continue;
				long voxels = (long)w * h * d;
				if (fileLength == voxels)
					return new RawCandidate(w, h, d, FileInfo.GRAY8, 0);
				else if (fileLength == voxels * 2)
					return new RawCandidate(w, h, d, FileInfo.GRAY16_UNSIGNED, 0);
				else if (fileLength == voxels * 4)
					return new RawCandidate(w, h, d, FileInfo.GRAY32_FLOAT, 0);
				else if (fileLength == voxels * 3)
					return new RawCandidate(w, h, d, FileInfo.RGB, 0);
				else if (fileLength > voxels && (fileLength - voxels) <= 65536)
					return new RawCandidate(w, h, d, FileInfo.GRAY8, fileLength - voxels);
				else if (fileLength > voxels * 2 && (fileLength - voxels * 2) <= 65536)
					return new RawCandidate(w, h, d, FileInfo.GRAY16_UNSIGNED, fileLength - voxels * 2);
			} catch (Exception ignored) {}
		}
		return null;
	}

	/** Automatically parses raw dimensions from filename and checks with file size. */
	public static FileInfo parseRawFileInfo(String path) {
		if (path==null || path.trim().isEmpty())
			return null;
		File f = new File(path);
		if (!f.exists() || !f.isFile())
			return null;
		long fileLength = f.length();
		if (fileLength<=0)
			return null;
		String name = f.getName();

		int bestW = 0, bestH = 0, bestD = 0;
		int bestType = FileInfo.GRAY8;
		long bestOffset = 0;
		boolean found = false;

		// 1. Try 3D dimensions in file name
		RawCandidate cand = match3dPattern(name, fileLength);

		// 2. Try parent directories (up to 4 levels)
		if (cand == null) {
			File p = f.getParentFile();
			int depth = 0;
			while (p != null && depth < 4) {
				cand = match3dPattern(p.getName(), fileLength);
				if (cand != null) break;
				p = p.getParentFile();
				depth++;
			}
		}

		// 3. Try sibling files in the same directory (e.g. 2105-2105-451.txt)
		if (cand == null) {
			File dir = f.getParentFile();
			if (dir != null && dir.isDirectory()) {
				File[] siblings = dir.listFiles();
				if (siblings != null) {
					for (File sib : siblings) {
						cand = match3dPattern(sib.getName(), fileLength);
						if (cand != null) break;
					}
				}
			}
		}

		// 4. CT Square Slice Factorization (W == H)
		if (cand == null) {
			java.util.List list = new java.util.ArrayList();
			for (int w = 500; w <= 4096; w++) {
				long w2 = (long)w * w;
				if (fileLength % w2 == 0) {
					long d = fileLength / w2;
					if (d >= 10 && d <= 5000) {
						list.add(new RawCandidate(w, w, (int)d, FileInfo.GRAY8, 0));
					}
				}
				if (fileLength % (w2 * 2) == 0) {
					long d = fileLength / (w2 * 2);
					if (d >= 10 && d <= 5000) {
						list.add(new RawCandidate(w, w, (int)d, FileInfo.GRAY16_UNSIGNED, 0));
					}
				}
			}
			if (list.size() == 1) {
				cand = (RawCandidate)list.get(0);
			} else if (list.size() > 1) {
				for (int i = 0; i < list.size(); i++) {
					RawCandidate c = (RawCandidate)list.get(i);
					if (c.d >= c.w * 0.1 && c.d <= c.w * 1.5) {
						cand = c;
						break;
					}
				}
				if (cand == null) cand = (RawCandidate)list.get(0);
			}
		}

		if (cand != null) {
			bestW = cand.w;
			bestH = cand.h;
			bestD = cand.d;
			bestType = cand.type;
			bestOffset = cand.offset;
			found = true;
		}

		// 2. Try 2D dimensions, e.g. -1689-1689, 512x512
		if (!found) {
			Pattern p2d = Pattern.compile("(?<!\\d)(\\d{2,5})[-_xX](\\d{2,5})(?!\\d)");
			Matcher m2d = p2d.matcher(name);
			while (m2d.find()) {
				try {
					int w = Integer.parseInt(m2d.group(1));
					int h = Integer.parseInt(m2d.group(2));
					if (w<=0 || h<=0) continue;
					long pixels = (long)w * h;
					if (fileLength == pixels) {
						bestW = w; bestH = h; bestD = 1; bestType = FileInfo.GRAY8; bestOffset = 0;
						found = true; break;
					} else if (fileLength == pixels * 2) {
						bestW = w; bestH = h; bestD = 1; bestType = FileInfo.GRAY16_UNSIGNED; bestOffset = 0;
						found = true; break;
					} else if (fileLength == pixels * 4) {
						bestW = w; bestH = h; bestD = 1; bestType = FileInfo.GRAY32_FLOAT; bestOffset = 0;
						found = true; break;
					} else if (fileLength == pixels * 3) {
						bestW = w; bestH = h; bestD = 1; bestType = FileInfo.RGB; bestOffset = 0;
						found = true; break;
					} else if (fileLength % pixels == 0) {
						long d = fileLength / pixels;
						if (d > 1 && d <= 65536) {
							bestW = w; bestH = h; bestD = (int)d; bestType = FileInfo.GRAY8; bestOffset = 0;
							found = true; break;
						}
					} else if (fileLength % (pixels * 2) == 0) {
						long d = fileLength / (pixels * 2);
						if (d > 1 && d <= 65536) {
							bestW = w; bestH = h; bestD = (int)d; bestType = FileInfo.GRAY16_UNSIGNED; bestOffset = 0;
							found = true; break;
						}
					}
				} catch (Exception ignored) {}
			}
		}

		if (!found)
			return null;

		FileInfo fi = new FileInfo();
		fi.fileFormat = FileInfo.RAW;
		fi.fileName = name;
		String parent = f.getParent();
		if (parent!=null)
			fi.directory = parent + File.separator;
		fi.width = bestW;
		fi.height = bestH;
		fi.nImages = bestD;
		fi.fileType = bestType;
		if (bestOffset > 2147483647L)
			fi.longOffset = bestOffset;
		else
			fi.offset = (int)bestOffset;

		fi.intelByteOrder = !name.toLowerCase().contains("be.raw") && !name.toLowerCase().contains("big_endian");

		// 3. Extract voxel size, e.g. -5um, _5um, -0.5um, -6mm
		Pattern pUnit = Pattern.compile("[-_](\\d+(?:\\.\\d+)?)\\s*(um|µm|nm|mm|cm|m)\\b", Pattern.CASE_INSENSITIVE);
		Matcher mUnit = pUnit.matcher(name);
		if (!mUnit.find() && f.getParent() != null) {
			mUnit = pUnit.matcher(f.getAbsolutePath());
		} else {
			mUnit.reset();
		}
		if (mUnit.find()) {
			try {
				double vSize = Double.parseDouble(mUnit.group(1));
				String unit = mUnit.group(2).toLowerCase();
				if (unit.equals("um") || unit.equals("µm"))
					unit = "µm";
				fi.pixelWidth = vSize;
				fi.pixelHeight = vSize;
				fi.pixelDepth = vSize;
				fi.unit = unit;
			} catch (Exception ignored) {}
		}

		return fi;
	}

	/** Automatically opens a raw file if dimensions can be parsed from its name and match the file length. */
	public static ImagePlus openAuto(String path) {
		FileInfo fi = parseRawFileInfo(path);
		if (fi==null)
			return null;

		long requiredBytes = (long)fi.width * fi.height * fi.nImages * fi.getBytesPerPixel();
		Runtime rt = Runtime.getRuntime();
		long maxMem = rt.maxMemory();
		long freeMem = maxMem - rt.totalMemory() + rt.freeMemory();

		ImagePlus imp = null;
		if (requiredBytes > freeMem * 0.75) {
			System.gc();
			freeMem = maxMem - rt.totalMemory() + rt.freeMemory();
		}

		if (requiredBytes > freeMem * 0.85) {
			IJ.showStatus("Opening large volume as Virtual Stack...");
			new FileInfoVirtualStack(fi);
			return WindowManager.getCurrentImage();
		}

		try {
			FileOpener fo = new FileOpener(fi);
			imp = fo.openImage();
		} catch (OutOfMemoryError e) {
			IJ.showStatus("Out of memory, switching to Virtual Stack...");
			System.gc();
			new FileInfoVirtualStack(fi);
			return WindowManager.getCurrentImage();
		}

		if (imp!=null) {
			if (fi.unit!=null && fi.pixelWidth>0) {
				Calibration cal = imp.getCalibration();
				cal.setUnit(fi.unit);
				cal.pixelWidth = fi.pixelWidth;
				cal.pixelHeight = fi.pixelHeight;
				cal.pixelDepth = fi.pixelDepth;
			}
			int n = imp.getStackSize();
			if (n>1) {
				imp.setDimensions(1, n, 1);
				imp.setSlice(n/2);
				ImageProcessor ip = imp.getProcessor();
				ip.resetMinAndMax();
				imp.setDisplayRange(ip.getMin(), ip.getMax());
			}
		}
		return imp;
	}

	/** Opens the image at 'filePath' using the format specified by 'fi'. */
	public static ImagePlus open(String filePath, FileInfo fi) {
		File f = new File(filePath);
		String parent = f.getParent();
		if (parent!=null)
			fi.directory = parent+ "/";
		fi.fileName = f.getName();
		return (new FileOpener(fi)).open(false);
	}	


	/** Opens all the images in the specified directory as a stack,
		using the format specified by 'fi'. */
	public static ImagePlus openAll(String directory, FileInfo fi) {
		ImagePlus imp = openAllVirtual(directory,fi);
		if (imp!=null)
			return imp.duplicate();
		else
			return null;
	}	

	/** Opens all the images in the specified directory as a virtual stack,
		using the format specified by 'fi'. */
	public static ImagePlus openAllVirtual(String directory, FileInfo fi) {
		String[] list = new File(directory).list();
		if (list==null)
			return null;
		FolderOpener fo = new FolderOpener();
		list = fo.trimFileList(list);
		list = fo.sortFileList(list);
		if (list==null)
			return null;
		directory = IJ.addSeparator(directory);
		FileInfo[] info = new FileInfo[list.length];
		for (int i=0; i<list.length; i++) {
			info[i] = (FileInfo)fi.clone();
			info[i].directory = directory;
			info[i].fileName = list[i];
		}
		VirtualStack stack = new FileInfoVirtualStack(info);
		ImagePlus imp = new ImagePlus(directory, stack);
		return imp;
	}	
	
}
