package org.openstatic.routeput;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

class ChannelLog implements Closeable
{
    private final File folder;
    private final Clock clock;
    private BufferedWriter writer;
    private LocalDate date;
    private boolean closed;

    ChannelLog(File folder) throws IOException
    {
        this(folder, Clock.systemDefaultZone());
    }

    ChannelLog(File folder, Clock clock) throws IOException
    {
        this.folder = folder;
        this.clock = clock;
        this.rotate();
    }

    private void rotate() throws IOException
    {
        if (this.closed)
        {
            throw new IOException("Channel log is closed");
        }
        LocalDate today = LocalDate.now(this.clock);
        if (!today.equals(this.date))
        {
            if (this.writer != null)
            {
                this.writer.close();
                this.writer = null;
            }
            this.writer = new BufferedWriter(new FileWriter(new File(this.folder, "channel." + today + ".log"), true));
            this.date = today;
        }
    }

    synchronized void write(String message) throws IOException
    {
        this.rotate();
        this.writer.write(message);
        this.writer.write("\n");
    }

    synchronized void flush() throws IOException
    {
        this.rotate();
        this.writer.flush();
    }

    @Override
    public synchronized void close() throws IOException
    {
        this.closed = true;
        if (this.writer != null)
        {
            try
            {
                this.writer.close();
            } finally {
                this.writer = null;
            }
        }
    }

    static void cleanRoot(File root, Clock clock, long folderTimeoutMillis, Set<String> protectedChannels) throws IOException
    {
        if (!root.exists())
        {
            return;
        }
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(root.toPath()))
        {
            for (Path folder : folders)
            {
                if (Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
                {
                    String channelName = folder.getFileName().toString();
                    try
                    {
                        if (folderTimeoutMillis > 0
                            && !protectedChannels.contains(channelName)
                            && isFolderExpired(folder, clock, folderTimeoutMillis))
                        {
                            deleteRecursive(folder);
                        } else {
                            cleanFolder(folder, clock);
                        }
                    } catch (IOException e) {
                        // The debug channel is itself a log sink; never route log failures through it.
                        System.err.println("Channel folder cleanup failed in \"" + folder + "\": " + e);
                    }
                }
            }
        }
    }

    // A folder is expired when nothing inside it (or the folder itself) has been
    // modified within the timeout window.
    private static boolean isFolderExpired(Path folder, Clock clock, long timeoutMillis) throws IOException
    {
        return newestModified(folder) < (clock.millis() - timeoutMillis);
    }

    private static long newestModified(Path path) throws IOException
    {
        long newest = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path))
            {
                for (Path entry : entries)
                {
                    newest = Math.max(newest, newestModified(entry));
                }
            }
        }
        return newest;
    }

    private static void deleteRecursive(Path path) throws IOException
    {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path))
            {
                for (Path entry : entries)
                {
                    deleteRecursive(entry);
                }
            }
        }
        Files.delete(path);
    }

    static void cleanFolder(Path folder, Clock clock) throws IOException
    {
        Path legacy = folder.resolve("channel.log");
        if (Files.isRegularFile(legacy, LinkOption.NOFOLLOW_LINKS))
        {
            try
            {
                LocalDate legacyDate = Files.getLastModifiedTime(legacy).toInstant().atZone(clock.getZone()).toLocalDate();
                Files.move(legacy, folder.resolve("channel." + legacyDate + ".legacy.log"));
            } catch (IOException e) {
                System.err.println("Channel log migration failed in \"" + folder + "\": " + e);
            }
        }
        LocalDate oldest = LocalDate.now(clock).minusDays(6);
        try (DirectoryStream<Path> logs = Files.newDirectoryStream(folder, "channel.*.log"))
        {
            for (Path log : logs)
            {
                String name = log.getFileName().toString();
                if (Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)
                    && name.matches("channel\\.\\d{4}-\\d{2}-\\d{2}(\\.legacy)?\\.log"))
                {
                    LocalDate logDate;
                    try
                    {
                        logDate = LocalDate.parse(name.substring(8, 18));
                    } catch (DateTimeParseException e) {
                        continue;
                    }
                    if (logDate.isBefore(oldest))
                    {
                        Files.delete(log);
                    }
                }
            }
        }
    }
}
