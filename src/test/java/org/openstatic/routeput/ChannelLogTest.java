package org.openstatic.routeput;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ChannelLogTest
{
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void rotatesAtMidnightAndAppendsAfterRestart() throws Exception
    {
        File folder = this.temp.newFolder();
        MutableClock clock = new MutableClock("2027-10-20T23:59:59Z");
        try (ChannelLog log = new ChannelLog(folder, clock))
        {
            log.write("before midnight");
            clock.instant = Instant.parse("2027-10-21T00:00:00Z");
            log.write("after midnight");
            log.flush();
            assertEquals("before midnight\n", Files.readString(folder.toPath().resolve("channel.2027-10-20.log")));
            assertEquals("after midnight\n", Files.readString(folder.toPath().resolve("channel.2027-10-21.log")));
        }
        try (ChannelLog log = new ChannelLog(folder, clock))
        {
            log.write("after restart");
        }
        assertEquals("after midnight\nafter restart\n",
            Files.readString(folder.toPath().resolve("channel.2027-10-21.log")));
    }

    @Test
    public void flushRotatesAnIdleWriter() throws Exception
    {
        File folder = this.temp.newFolder();
        MutableClock clock = new MutableClock("2027-10-20T23:59:59Z");
        try (ChannelLog log = new ChannelLog(folder, clock))
        {
            log.write("buffered");
            clock.instant = Instant.parse("2027-10-21T00:00:00Z");
            log.flush();
            assertEquals("buffered\n", Files.readString(folder.toPath().resolve("channel.2027-10-20.log")));
            assertTrue(Files.exists(folder.toPath().resolve("channel.2027-10-21.log")));
        }
    }

    @Test
    public void keepsExactlySevenCalendarDaysAndIgnoresOtherFiles() throws Exception
    {
        Path folder = this.temp.newFolder().toPath();
        for (int day = 1; day <= 20; day++)
        {
            Files.writeString(folder.resolve(String.format("channel.2027-10-%02d.log", day)), "log");
        }
        String[] untouched = {"properties.json", "secureProperties.json", "other.log",
            "channel.2027-02-30.log", "channel.notes.log", "channel.2027-10-01.log.backup"};
        for (String name : untouched)
        {
            Files.writeString(folder.resolve(name), "keep");
        }
        Files.createDirectory(folder.resolve("channel.2027-01-01.log"));
        ChannelLog.cleanFolder(folder, new MutableClock("2027-10-20T01:00:00Z"));
        for (int day = 1; day <= 20; day++)
        {
            assertEquals(day >= 14, Files.exists(folder.resolve(String.format("channel.2027-10-%02d.log", day))));
        }
        for (String name : untouched)
        {
            assertEquals("keep", Files.readString(folder.resolve(name)));
        }
        assertTrue(Files.isDirectory(folder.resolve("channel.2027-01-01.log")));
    }

    @Test
    public void migratesLegacyLogsAndCleansInactiveChannels() throws Exception
    {
        File root = this.temp.newFolder();
        Path recent = Files.createDirectory(root.toPath().resolve("recent"));
        Path old = Files.createDirectory(root.toPath().resolve("inactive"));
        Files.writeString(recent.resolve("channel.2027-10-19.log"), "new log");
        Files.writeString(recent.resolve("channel.log"), "legacy log");
        Files.setLastModifiedTime(recent.resolve("channel.log"), FileTime.from(Instant.parse("2027-10-19T12:00:00Z")));
        Files.writeString(old.resolve("channel.log"), "expired");
        Files.setLastModifiedTime(old.resolve("channel.log"), FileTime.from(Instant.parse("2027-10-13T12:00:00Z")));
        ChannelLog.cleanRoot(root, new MutableClock("2027-10-20T12:00:00Z"), 0L, java.util.Set.of());
        assertFalse(Files.exists(recent.resolve("channel.log")));
        assertEquals("legacy log", Files.readString(recent.resolve("channel.2027-10-19.legacy.log")));
        assertEquals("new log", Files.readString(recent.resolve("channel.2027-10-19.log")));
        assertFalse(Files.exists(old.resolve("channel.log")));
        assertFalse(Files.exists(old.resolve("channel.2027-10-13.legacy.log")));
        ChannelLog.cleanRoot(root, new MutableClock("2027-10-26T12:00:00Z"), 0L, java.util.Set.of());
        assertFalse(Files.exists(recent.resolve("channel.2027-10-19.legacy.log")));
        assertFalse(Files.exists(recent.resolve("channel.2027-10-19.log")));
    }

    @Test
    public void usesLocalDateForLogsAndLegacyMigration() throws Exception
    {
        File folder = this.temp.newFolder();
        Clock clock = Clock.fixed(Instant.parse("2027-10-21T01:00:00Z"), ZoneId.of("America/New_York"));
        Files.writeString(folder.toPath().resolve("channel.log"), "legacy");
        Files.setLastModifiedTime(folder.toPath().resolve("channel.log"), FileTime.from(clock.instant()));
        ChannelLog.cleanFolder(folder.toPath(), clock);
        try (ChannelLog log = new ChannelLog(folder, clock))
        {
            log.write("local date");
        }
        assertTrue(Files.exists(folder.toPath().resolve("channel.2027-10-20.log")));
        assertTrue(Files.exists(folder.toPath().resolve("channel.2027-10-20.legacy.log")));
    }

    @Test
    public void neverOverwritesAnExistingLegacyArchive() throws Exception
    {
        Path folder = this.temp.newFolder().toPath();
        Clock clock = new MutableClock("2027-10-20T12:00:00Z");
        Files.writeString(folder.resolve("channel.log"), "original");
        Files.setLastModifiedTime(folder.resolve("channel.log"), FileTime.from(clock.instant()));
        Files.writeString(folder.resolve("channel.2027-10-20.legacy.log"), "archive");
        Files.writeString(folder.resolve("channel.2027-10-01.log"), "expired");
        ChannelLog.cleanFolder(folder, clock);
        assertEquals("original", Files.readString(folder.resolve("channel.log")));
        assertEquals("archive", Files.readString(folder.resolve("channel.2027-10-20.legacy.log")));
        assertFalse(Files.exists(folder.resolve("channel.2027-10-01.log")));
    }

    @Test
    public void closedLogCannotBeReopenedByRollover() throws Exception
    {
        File folder = this.temp.newFolder();
        MutableClock clock = new MutableClock("2027-10-20T12:00:00Z");
        ChannelLog log = new ChannelLog(folder, clock);
        log.close();
        clock.instant = Instant.parse("2027-10-21T12:00:00Z");
        assertThrows(IOException.class, () -> log.write("after close"));
        assertThrows(IOException.class, log::flush);
        assertFalse(Files.exists(folder.toPath().resolve("channel.2027-10-21.log")));
    }

    @Test
    public void destroysStaleChannelFoldersButKeepsFreshAndProtectedOnes() throws Exception
    {
        File root = this.temp.newFolder();
        Instant now = Instant.now();
        long thirtyDays = 30L * 24L * 60L * 60L * 1000L;
        Path stale = makeChannelFolder(root, "stale", now.minusMillis(40L * 24L * 60L * 60L * 1000L));
        Path fresh = makeChannelFolder(root, "fresh", now.minusMillis(2L * 24L * 60L * 60L * 1000L));
        Path live = makeChannelFolder(root, "live", now.minusMillis(40L * 24L * 60L * 60L * 1000L));
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ChannelLog.cleanRoot(root, clock, thirtyDays, java.util.Set.of("live"));
        assertFalse("untouched folder should be destroyed", Files.exists(stale));
        assertTrue("recently modified folder should remain", Files.exists(fresh));
        assertTrue("live channel folder must never be destroyed", Files.exists(live));
    }

    @Test
    public void folderDestructionDisabledWhenTimeoutIsZero() throws Exception
    {
        File root = this.temp.newFolder();
        Instant now = Instant.now();
        Path stale = makeChannelFolder(root, "stale", now.minusMillis(400L * 24L * 60L * 60L * 1000L));
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ChannelLog.cleanRoot(root, clock, 0L, java.util.Set.of());
        assertTrue("timeout of 0 disables folder destruction", Files.exists(stale));
    }

    // Creates a channel folder with properties and a log, aging every entry and the
    // folder itself to the supplied instant (folder set last so nothing re-touches it).
    private Path makeChannelFolder(File root, String name, Instant modified) throws IOException
    {
        Path folder = Files.createDirectory(root.toPath().resolve(name));
        Files.writeString(folder.resolve("properties.json"), "{}");
        Files.writeString(folder.resolve("channel.2024-01-01.log"), "old log");
        FileTime time = FileTime.from(modified);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder))
        {
            for (Path entry : entries)
            {
                Files.setLastModifiedTime(entry, time);
            }
        }
        Files.setLastModifiedTime(folder, time);
        return folder;
    }

    private static class MutableClock extends Clock
    {
        private Instant instant;

        MutableClock(String instant)
        {
            this.instant = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return Clock.fixed(this.instant, zone);
        }

        @Override
        public Instant instant()
        {
            return this.instant;
        }
    }
}
