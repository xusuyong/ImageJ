using System;
using System.IO;
using System.Diagnostics;
using System.Text;
using System.Windows.Forms;

namespace ImageJLauncher
{
    static class Program
    {
        [STAThread]
        static void Main(string[] args)
        {
            try
            {
                string exePath = System.Reflection.Assembly.GetExecutingAssembly().Location;
                string appDir = Path.GetDirectoryName(exePath);

                string cfgPath = Path.Combine(appDir, "ImageJ-CT.cfg");
                if (!File.Exists(cfgPath))
                {
                    cfgPath = Path.Combine(appDir, "ImageJ.cfg");
                }

                string baseDir = appDir;
                string javaRelativePath = @"jre\bin\javaw.exe";
                string javaArgs = "-Xmx8000m -cp ij.jar ij.ImageJ";

                if (File.Exists(cfgPath))
                {
                    string[] lines = File.ReadAllLines(cfgPath);
                    if (lines.Length > 0 && !string.IsNullOrWhiteSpace(lines[0]))
                    {
                        string line0 = lines[0].Trim();
                        baseDir = line0 == "." ? appDir : Path.Combine(appDir, line0);
                    }
                    if (lines.Length > 1 && !string.IsNullOrWhiteSpace(lines[1]))
                    {
                        javaRelativePath = lines[1].Trim();
                    }
                    if (lines.Length > 2 && !string.IsNullOrWhiteSpace(lines[2]))
                    {
                        javaArgs = lines[2].Trim();
                    }
                }

                string javaExe = Path.IsPathRooted(javaRelativePath)
                    ? javaRelativePath
                    : Path.Combine(baseDir, javaRelativePath);

                if (!File.Exists(javaExe))
                {
                    javaExe = "javaw.exe";
                }

                StringBuilder argBuilder = new StringBuilder();
                argBuilder.Append(javaArgs);

                if (args != null && args.Length > 0)
                {
                    foreach (string arg in args)
                    {
                        argBuilder.Append(" ");
                        argBuilder.Append(QuoteArgument(arg));
                    }
                }

                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = javaExe;
                psi.Arguments = argBuilder.ToString();
                psi.WorkingDirectory = baseDir;
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;

                Process.Start(psi);
            }
            catch (Exception ex)
            {
                MessageBox.Show("Failed to launch ImageJ-CT:\n" + ex.Message, "ImageJ-CT Launch Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        static string QuoteArgument(string arg)
        {
            if (string.IsNullOrEmpty(arg)) return "\"\"";
            string escaped = arg.Replace("\"", "\\\"");
            if (escaped.EndsWith("\\"))
            {
                escaped += "\\";
            }
            return "\"" + escaped + "\"";
        }
    }
}
