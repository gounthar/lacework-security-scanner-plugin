package io.jenkins.plugins.laceworkscannerbuildstep;

import hudson.*;
import hudson.Launcher.ProcStarter;
import hudson.util.ArgumentListBuilder;
import java.io.File;
import java.io.PrintStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;

/**
 * This class does the actual lw-scanner execution.
 *
 * @author Alan Nix
 */
public class LaceworkScannerExecuter {

    public static int execute(Run<?, ?> build, FilePath workspace, EnvVars env, Launcher launcher,
            TaskListener listener, String laceworkAccountName, Secret laceworkAccessToken, String customFlags,
            boolean fixableOnly, String imageName, String imageTag, String outputHtmlName, boolean noPull,
            boolean evaluatePolicies, boolean saveToLacework, boolean scanLibraryPackages, String tags) {

        PrintStream print_stream = null;
        try {
            // Check to see if environment variables were provided as imageName/imageTag
            imageName = env.expand(imageName);
            imageTag = env.expand(imageTag);

            ArgumentListBuilder args = new ArgumentListBuilder();

            String buildId = env.get("BUILD_ID");
            String buildName = env.get("JOB_NAME").trim();
            buildName = buildName.replaceAll("\\s+", "");

            File htmlFile = new File(build.getRootDir(), outputHtmlName);

            String cssFileName = "laceworkstyles.css";
            File cssFile = new File(build.getRootDir(), cssFileName);

            args.add("lw-scanner", "image", "evaluate", imageName, imageTag);

            // Add Lacework authentication if no environment variables
            // This allows for override in a specific pipeline
            if (env.get("LW_ACCOUNT_NAME") == null) {
                args.add("--account-name", laceworkAccountName);
            }
            if (env.get("LW_ACCESS_TOKEN") == null) {
                args.add("--access-token");
                args.addMasked(laceworkAccessToken);
            }

            args.add("--build-id", buildId);
            args.add("--build-plan", buildName);

            args.add("--html");
            args.add("--html-file", htmlFile.getAbsolutePath());

            if (fixableOnly) {
                args.add("--fixable");
            }

            if (noPull) {
                args.add("--no-pull");
            }

            if (evaluatePolicies) {
                args.add("--policy");
            }

            if (saveToLacework) {
                args.add("--save");
            }

            if (scanLibraryPackages) {
                args.add("--scan-library-packages");
            }

            if (tags != null && !tags.equals("")) {
                args.add("--tags", tags);
            }

            if (customFlags != null && !customFlags.equals("")) {
                args.addTokenized(customFlags);
            }

            File outFile = new File(build.getRootDir(), "output");
            ProcStarter ps = launcher.launch();
            ps.cmds(args);
            ps.stdin(null);
            print_stream = new PrintStream(outFile, "UTF-8");
            ps.stderr(print_stream);
            ps.stdout(print_stream);
            ps.quiet(true);
            listener.getLogger().println(args.toString());
            int exitCode = ps.join(); // RUN !

            // HTML
            FilePath htmlFilePath = new FilePath(htmlFile);
            FilePath htmlTarget = new FilePath(workspace, outputHtmlName);
            htmlFilePath.copyTo(htmlTarget);

            String htmlOutput = htmlFilePath.readToString();
            cleanHtmlOutput(htmlOutput, htmlTarget, listener);

            // CSS
            FilePath cssFilePath = new FilePath(cssFile);
            generateCssFile(htmlOutput, cssFilePath, listener);
            FilePath cssTarget = new FilePath(workspace, cssFileName);
            cssFilePath.copyTo(cssTarget);

            return exitCode;

        } catch (RuntimeException e) {
            listener.getLogger().println("RuntimeException:" + e.toString());
            return -1;
        } catch (Exception e) {
            listener.getLogger().println("Exception:" + e.toString());
            return -1;
        } finally {
            if (print_stream != null) {
                print_stream.close();
            }
        }
    }

    private static void generateCssFile(String scanOutput, FilePath target, TaskListener listener) {

        String cssContent = "";

        Pattern pattern = Pattern.compile("(?s)<style>(.*?)</style>");
        Matcher matcher = pattern.matcher(scanOutput);

        while (matcher.find()) {
            cssContent = cssContent + matcher.group(1) + "\n";
        }

        try {
            target.write(cssContent, "UTF-8");
        } catch (Exception e) {
            listener.getLogger().println("Failed to save CSS file.");
        }
    }

    // Clean the inline CSS from HTML
    private static boolean cleanHtmlOutput(String scanOutput, FilePath target, TaskListener listener) {

        int htmlStart = scanOutput.indexOf("<!DOCTYPE html>");
        if (htmlStart == -1) {
            listener.getLogger().println(scanOutput);
            return false;
        }
        listener.getLogger().println(scanOutput.substring(0, htmlStart));
        int htmlEnd = scanOutput.lastIndexOf("</html>") + 7;

        // Remove <style> & <script> tags (Jenkins CSP doesn't allow)
        scanOutput = scanOutput.substring(htmlStart, htmlEnd);

        String styleRegex = "(?s)<style.*?(/>|</style>)";
        scanOutput = scanOutput.replaceAll(styleRegex, "");

        // String scriptRegex = "(?s)<script.*?(/>|</script>)";
        // scanOutput = scanOutput.replaceAll(scriptRegex, "");

        int headEnd = scanOutput.lastIndexOf("</head>");
        scanOutput = insert(scanOutput, "<link rel=\"stylesheet\" type=\"text/css\" href=\"laceworkstyles.css\">",
                headEnd);
        try {
            target.write(scanOutput, "UTF-8");
        } catch (Exception e) {
            listener.getLogger().println("Failed to save HTML report.");
        }

        return true;
    }

    private static String insert(String orginalStr, String insertStr, int index) {
        String orginalStrBegin = orginalStr.substring(0, index);
        String orginalStrEnd = orginalStr.substring(index);
        return orginalStrBegin + insertStr + orginalStrEnd;
    }
}