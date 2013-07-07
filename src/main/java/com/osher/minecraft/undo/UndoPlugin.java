/**
 * SaveMeBackup minecraft/tekkit plugin
 *
 * Copyright (c) 2013 Seth Osher. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.osher.minecraft.undo;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class UndoPlugin extends JavaPlugin {

    private int hourlyTaskId = -1;
    private int dailyTaskId = -1;
    private boolean verboseLog = false;
    private boolean dailyOff;
    private boolean periodicOff;

    @Override
    public void onEnable() {
        try {
            // enable default config
            saveDefaultConfig();

            // load log config
            verboseLog = getConfig().getString("verbose-log").equalsIgnoreCase("true");
            dailyOff = getConfig().getString("daily-off").equalsIgnoreCase("true");
            periodicOff = getConfig().getString("periodic-off").equalsIgnoreCase("true");

            // schedule jobs
            if(!periodicOff)
                scheduleHourlyJob();
            if(!dailyOff)
                scheduleDailyJob();


        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Exception in savemebackup.onEnable: " + t.getMessage(), t);
        }
    }

    private void scheduleDailyJob() {
        // Setup daily job
        // For confusion, we calculate this in millis
        // then convert to tics (1 tick = 1/20s = 50ms)
        String atTime = getConfig().getString("daily-at");
        atTime = atTime.replaceAll("\"", "");
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss");
        try {
            Date time = format.parse(atTime);
            Date now = new Date(System.currentTimeMillis());
            long todayMillis = now.getTime() - (now.getHours() * 3600 + now.getMinutes() * 60 + now.getSeconds()) * 1000;   // This is today at midnight
            Date next = new Date(todayMillis + time.getTime());
            long millisDiff = next.getTime() - now.getTime(); // Milliseconds until I need to run the daily backup
            if (millisDiff < 0) // its already passed today, so schedule for tomorrow
            {
                next = new Date(next.getTime() + 24 * 3600 * 1000);
                millisDiff = next.getTime() - now.getTime();
            }
            long seconds = millisDiff / 1000;
            long tics = seconds * 20;
            long hours = seconds / 3600;
            long mins = (seconds - hours * 3600) / 60;
            long secs = (seconds - hours * 3600 - mins * 60);

            getLogger().info("Scheduling daily backup for:  " + next.toString() + " in " + hours + "h " + mins + "m " + secs + "s");
            dailyTaskId = getServer().getScheduler().scheduleAsyncRepeatingTask(this, new DailyJob(), tics, 24 * 3600 * 20);  // just in case, setup repeating at 24hours
        } catch (Throwable t) {
            getLogger().warning("Unable to parse time not scheduling daily auto save: " + atTime);
        }
    }

    private void scheduleHourlyJob() {
        // Setup hourly job
        String frequency = getConfig().getString("frequency");
        frequency = frequency.toLowerCase();
        int multiplier;
        if (frequency.endsWith("m")) {
            multiplier = 60;
        } else if (frequency.endsWith("h")) {
            multiplier = 360;
        } else {
            multiplier = 1;
        }
        try {
            String base = frequency.substring(0, frequency.length() - 1);
            int secs = Integer.parseInt(base);
            secs *= multiplier;
            hourlyTaskId = getServer().getScheduler().scheduleAsyncRepeatingTask(this, new HourlyJob(), secs * 20, secs * 20);
            getLogger().info("Setup backup task id " + hourlyTaskId + " at " + secs + "s");
        } catch (Throwable t) {
            getLogger().warning("Unable to parse frequency not scheduling hourly auto save: " + frequency);
        }
    }

    @Override
    public void onDisable() {
        // un-schedule my scheduled jobs
        try {
            if (hourlyTaskId >= 0) {
                getLogger().info("Stopping scheduled hourly backup task");
                getServer().getScheduler().cancelTask(hourlyTaskId);
                hourlyTaskId = -1;
            }
            if (dailyTaskId >= 0) {
                getLogger().info("Stopping scheduled daily backup task");
                getServer().getScheduler().cancelTask(dailyTaskId);
                dailyTaskId = -1;
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Exception in savemebackup.onDisable: " + t.getMessage(), t);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        try {
            if ("savemebackup".equals(cmd.getName())) {
                boolean daily;
                if (args.length < 1) {
                    sender.sendMessage("savemebackup hourly|daily - Please specify which to run!");
                    return true;
                }
                if ("daily".equals(args[0]))
                    daily = true;
                else if ("hourly".equals(args[0]))
                    daily = false;
                else {
                    sender.sendMessage("savemebackup hourly|daily - Please specify which to run!");
                    return true;
                }
                return doSaveMeBackup(sender, daily);
            }
        } catch (Throwable t) {
            sender.sendMessage("Exception caught in savemebackup (see log for details): " + t.getMessage());
            getLogger().log(Level.WARNING, "Exception in savemebackup.onCommand: " + t.getMessage(), t);
        }
        return false;
    }

    // The main executor
    private boolean doSaveMeBackup(CommandSender sender, boolean daily) throws IOException, ExecutionException, InterruptedException {

        // If on main server thread, kick off on async thread
        if (getServer().isPrimaryThread()) {
            getServer().getScheduler().scheduleAsyncDelayedTask(this, daily ? new DailyJob() : new HourlyJob(), 1);
            sender.sendMessage("Scheduled immediate savemebackup backup");
            return true;
        }

        // Force a file sync and turn off auto save
        String result = (String) getServer().getScheduler().callSyncMethod(this, new SaveOff()).get();
        if (!"ok".equals(result)) {
            getLogger().warning("Failed to turn off auto-save, aborting backup.");
            return true;
        }

        try {

            getLogger().info("savemebackup " + (daily ? "daily" : "hourly") + " started");

            List<String> files = getConfig().getStringList("files");
            List<String> folders = getConfig().getStringList("folders");

            String destZip;
            int copies;
            if (!daily) {
                destZip = getConfig().getString("save");
                copies = getConfig().getInt("keep");
            } else {
                destZip = getConfig().getString("saveday");
                copies = getConfig().getInt("keepday");
            }
            getLogger().fine("Output to: " + destZip);
            getLogger().fine("Keep copies: " + copies);

            // Do rolling files
            renameOldFiles(destZip, copies);

            // Create the zip file
            createZip(sender, files, folders, destZip);

            getLogger().info("savemebackup " + (daily ? "daily" : "hourly") + " completed");

        } finally {
            // save-on
            result = (String) getServer().getScheduler().callSyncMethod(this, new SaveOn()).get();
            if (!"ok".equals(result)) {
                getLogger().warning("Failed to call save-on");
            }
        }
        return true;
    }

    private void createZip(CommandSender sender, List<String> files, List<String> folders, String destZip) throws IOException {
        // Create new zip
        File zipFile = new File(destZip);
        if (!zipFile.getParentFile().exists()) {
            getLogger().info("Creating folder for saves: " + zipFile.getParentFile().getAbsolutePath());
            if (!zipFile.getParentFile().mkdirs()) {
                getLogger().warning("Failed to create saves folder, expect more errors: " + zipFile.getParentFile().getAbsolutePath());
            }
        }

        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(destZip));
        try {

            // Add the files
            for (String f : files) {
                if (sender != null && verboseLog)
                    sender.sendMessage("Next File: " + f);
                processFile(new File(f), zip);
            }

            for (String fld : folders) {
                if (sender != null && verboseLog)
                    sender.sendMessage("Next Folder: " + fld);
                processFile(new File(fld), zip);
            }
        } finally {
            zip.close();
        }
    }

    // Process a file or folder
    // Folders processed recursively
    private void processFile(File file, ZipOutputStream zip) throws IOException {
        if (!file.exists()) {
            getLogger().warning("Cannot backup file/folder, does not exist: " + file.getAbsolutePath());
            return;
        }

        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files == null)
                return;
            for (File child : files) {
                processFile(child, zip);
            }
            return;
        }

        // Add file to zip
        if(verboseLog)
            getLogger().fine("Adding to zip: " + file.getAbsolutePath());

        zip.putNextEntry(new ZipEntry(file.getPath()));
        FileInputStream reader = new FileInputStream(file);
        try {
            byte[] buffer = new byte[1024 * 4];
            int cnt;
            while ((cnt = reader.read(buffer)) > 0) {
                zip.write(buffer, 0, cnt);
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "IOException on " + file.getAbsolutePath() + ": " + e.getMessage(), e);
        } finally {
            zip.closeEntry();
            reader.close();
        }

    }

    // Find destZip + .1 .. destZip + .copies
    // Move each one down
    private void renameOldFiles(String destZip, int copies) {

        // Count down from max copies ...
        for (int i = copies; i > 0; i--) {
            String newName = destZip + "." + i;
            String oldName = destZip + "." + (i - 1);
            if (i == 1)
                oldName = destZip;
            File fOld = new File(oldName);
            if (fOld.exists()) {
                // Cleanup target file
                File fNew = new File(newName);
                if (fNew.exists()) {
                    if (!fNew.delete()) {
                        getLogger().log(Level.WARNING, "Unable to delete: " + fNew.getAbsolutePath());
                    }
                }

                // rename
                if (!fOld.renameTo(fNew)) {
                    getLogger().log(Level.WARNING, "Failed to rename: " + fOld.getAbsolutePath() + " to " + fNew.getAbsolutePath());
                } else {
                    getLogger().log(Level.FINE, "Renamed " + fOld.getAbsoluteFile() + " to " + fNew.getAbsolutePath());
                }
            }

        }
    }

    // Hourly job runnable
    private class HourlyJob implements Runnable {
        @Override
        public void run() {
            try {
                doSaveMeBackup(null, false);
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Exception in short job hander: " + t.getMessage(), t);
            }
        }
    }

    // Daily job runnable
    private class DailyJob implements Runnable {
        @Override
        public void run() {
            try {
                doSaveMeBackup(null, true);
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Exception in short job hander: " + t.getMessage(), t);
            }
        }
    }

    // Save off runnable
    private class SaveOff implements Callable<Object> {
        @Override
        public Object call() throws Exception {
            if (!getServer().isPrimaryThread()) {
                getLogger().warning("SaveOff not on primary thread!");
            }
            // save-all
            getServer().dispatchCommand(getServer().getConsoleSender(), "save-all");
            // save-off
            getServer().dispatchCommand(getServer().getConsoleSender(), "save-off");

            return "ok";
        }
    }

    // Save on runnable
    private class SaveOn implements Callable<Object> {
        @Override
        public Object call() throws Exception {
            if (!getServer().isPrimaryThread()) {
                getLogger().warning("SaveOn not on primary thread!");
            }
            // save-on
            getServer().dispatchCommand(getServer().getConsoleSender(), "save-on");

            return "ok";
        }
    }

}
