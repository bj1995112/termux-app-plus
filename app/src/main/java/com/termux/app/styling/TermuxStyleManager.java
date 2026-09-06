package com.termux.app.styling;

import android.content.Context;
import android.content.Intent;
import android.util.AtomicFile;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TermuxStyleManager {

    private static final String LOG_TAG = "TermuxStyleManager";
    public static final String DEFAULT_NAME = "Default";

    public static class StyleItem implements Comparable<StyleItem> {
        public final String fileName;
        public final String displayName;

        public StyleItem(String fileName) {
            this.fileName = fileName;
            if (DEFAULT_NAME.equalsIgnoreCase(fileName)) {
                this.displayName = "默认 (Default)";
            } else {
                String name = fileName.replace('-', ' ');
                int dotIndex = name.lastIndexOf('.');
                if (dotIndex != -1) {
                    name = name.substring(0, dotIndex);
                }
                this.displayName = capitalize(name);
            }
        }

        private static String capitalize(String str) {
            boolean lastWhitespace = true;
            char[] chars = str.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                if (Character.isLetter(chars[i])) {
                    if (lastWhitespace) {
                        chars[i] = Character.toUpperCase(chars[i]);
                    }
                    lastWhitespace = false;
                } else {
                    lastWhitespace = Character.isWhitespace(chars[i]);
                }
            }
            return new String(chars);
        }

        @Override
        public String toString() {
            return displayName;
        }

        @Override
        public int compareTo(StyleItem o) {
            if (DEFAULT_NAME.equalsIgnoreCase(this.fileName)) return -1;
            if (DEFAULT_NAME.equalsIgnoreCase(o.fileName)) return 1;
            return this.displayName.compareToIgnoreCase(o.displayName);
        }
    }

    public static List<StyleItem> getColorSchemes(Context context) {
        List<StyleItem> list = new ArrayList<>();
        list.add(new StyleItem(DEFAULT_NAME));
        try {
            String[] files = context.getAssets().list("styling/colors");
            if (files != null) {
                for (String file : files) {
                    if (file.endsWith(".properties")) {
                        list.add(new StyleItem(file));
                    }
                }
            }
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to list color assets: " + e.getMessage());
        }
        Collections.sort(list);
        return list;
    }

    public static List<StyleItem> getFonts(Context context) {
        List<StyleItem> list = new ArrayList<>();
        list.add(new StyleItem(DEFAULT_NAME));
        try {
            String[] files = context.getAssets().list("styling/fonts");
            if (files != null) {
                for (String file : files) {
                    if (file.endsWith(".ttf") || file.endsWith(".otf")) {
                        list.add(new StyleItem(file));
                    }
                }
            }
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to list font assets: " + e.getMessage());
        }
        Collections.sort(list);
        return list;
    }

    public static boolean applyColorScheme(Context context, String fileName) {
        return copyAssetToTermuxDir(context, "styling/colors", fileName, "colors.properties", true);
    }

    public static boolean applyFont(Context context, String fileName) {
        return copyAssetToTermuxDir(context, "styling/fonts", fileName, "font.ttf", false);
    }

    public static boolean resetToDefault(Context context) {
        boolean c = applyColorScheme(context, DEFAULT_NAME);
        boolean f = applyFont(context, DEFAULT_NAME);
        return c && f;
    }

    private static boolean copyAssetToTermuxDir(Context context, String assetFolder, String fileName, String targetFileName, boolean isColor) {
        try {
            File homeDir = new File(context.getFilesDir(), "home");
            File termuxDir = new File(homeDir, ".termux");
            if (!termuxDir.isDirectory() && !termuxDir.mkdirs()) {
                Logger.logError(LOG_TAG, "Cannot create directory: " + termuxDir.getAbsolutePath());
                return false;
            }

            File destinationFile = new File(termuxDir, targetFileName).getCanonicalFile();
            destinationFile.setWritable(true);
            if (destinationFile.getParentFile() != null) {
                destinationFile.getParentFile().setWritable(true);
                destinationFile.getParentFile().setExecutable(true);
            }

            AtomicFile atomicFile = new AtomicFile(destinationFile);
            FileOutputStream out = atomicFile.startWrite();

            if (DEFAULT_NAME.equalsIgnoreCase(fileName)) {
                if (isColor) {
                    byte[] comment = "# Using default color theme.\n".getBytes(StandardCharsets.UTF_8);
                    out.write(comment);
                } else {
                    // Empty or delete for default font
                }
            } else {
                try (InputStream in = context.getAssets().open(assetFolder + "/" + fileName)) {
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }
            }
            atomicFile.finishWrite(out);

            // Broadcast reload intent
            notifyReloadStyle(context, isColor);
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write " + targetFileName, e);
            return false;
        }
    }

    public static void notifyReloadStyle(Context context, boolean isColor) {
        Intent reloadIntent = new Intent(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        reloadIntent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_RELOAD_STYLE, isColor ? "colors" : "font");
        reloadIntent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true);
        context.sendBroadcast(reloadIntent);
    }
}
