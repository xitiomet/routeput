package org.openstatic.routeput;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class BLOBManagerTest
{
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void sharesExistingBlobsAndPreservesThemOnShutdown() throws Exception
    {
        Path root = this.temp.newFolder().toPath();
        Path channel = Files.createDirectory(root.resolve("shared"));
        Path blob = Files.writeString(channel.resolve("cached.txt"), "already here");
        assertEquals(root.toRealPath(), runClient(root.toString(), "cached").toRealPath());
        assertEquals("already here", Files.readString(blob));
    }

    @Test
    public void createsSharedStorageWithoutDeletingItOnShutdown() throws Exception
    {
        Path root = this.temp.getRoot().toPath().resolve("new").resolve("blobs");
        assertEquals(root, runClient(root.toString(), "client"));
        assertTrue(Files.isDirectory(root));
    }

    @Test
    public void nullSettingsUsesTheSameSharedStorage() throws Exception
    {
        Path root = this.temp.newFolder().toPath();
        assertEquals(root, runClient(root.toString(), "null"));
        assertTrue(Files.isDirectory(root));
    }

    @Test
    public void absentEnvironmentUsesTemporaryStorage() throws Exception
    {
        assertFalse(Files.exists(runClient(null, "client")));
    }

    @Test
    public void blankEnvironmentUsesTemporaryStorage() throws Exception
    {
        assertFalse(Files.exists(runClient(" ", "null")));
    }

    @Test
    public void explicitSettingsOverrideClientStorageButAreNotOverriddenByIt() throws Exception
    {
        Path root = this.temp.newFolder().toPath();
        assertEquals(root, runClient(root.toString(), "override"));
        assertTrue(Files.isDirectory(root));
    }

    @Test
    public void invalidSharedRootFailsWithoutTemporaryFallback() throws Exception
    {
        Path root = this.temp.newFile().toPath();
        runClient(root.toString(), "invalid");
        assertTrue(Files.isRegularFile(root));
    }

    @Test
    public void serverUsesEnvironmentWhenStorageSettingIsAbsent() throws Exception
    {
        Path root = this.temp.getRoot().toPath().resolve("server-blobs");
        assertEquals(root, runClient(root.toString(), "server"));
        assertTrue(Files.isDirectory(root));
    }

    @Test
    public void serverUsesDefaultWhenEnvironmentIsAbsent() throws Exception
    {
        Path root = runClient(null, "server");
        assertEquals("blob", root.getFileName().toString());
        assertTrue(Files.isDirectory(root));
    }

    @Test
    public void serverUsesDefaultWhenEnvironmentIsBlank() throws Exception
    {
        Path root = runClient(" ", "server");
        assertEquals("blob", root.getFileName().toString());
        assertTrue(Files.isDirectory(root));
    }

    @Test
    public void serverExplicitSettingOverridesEnvironment() throws Exception
    {
        Path environmentRoot = this.temp.getRoot().toPath().resolve("unused");
        Path root = runClient(environmentRoot.toString(), "server-explicit");
        assertEquals("explicit", root.getFileName().toString());
        assertTrue(Files.isDirectory(root));
        assertFalse(Files.exists(environmentRoot));
    }

    private Path runClient(String sharedRoot, String mode) throws Exception
    {
        Path work = this.temp.newFolder().toPath();
        Path result = work.resolve("root.txt");
        Path output = work.resolve("output.txt");
        ProcessBuilder builder = new ProcessBuilder(
            new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
            "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            Probe.class.getName(), mode, result.toString());
        builder.directory(work.toFile());
        if (sharedRoot == null)
        {
            builder.environment().remove("ROUTEPUT_BLOBROOT");
        } else {
            builder.environment().put("ROUTEPUT_BLOBROOT", sharedRoot);
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        Process process = builder.start();
        try
        {
            assertTrue("Client process timed out", process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(Files.readString(output), 0, process.exitValue());
            return Path.of(Files.readString(result));
        } finally {
            if (process.isAlive())
            {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    public static class Probe
    {
        public static void main(String[] args) throws Exception
        {
            String mode = args[0];
            if (mode.startsWith("server"))
            {
                JSONObject settings = new JSONObject().put("blobStorageTimeout", 0);
                boolean explicit = "server-explicit".equals(mode);
                if (explicit)
                {
                    settings.put("blobStorageRoot", "./explicit/");
                }
                BLOBManager.init(settings);
                String sharedRoot = System.getenv("ROUTEPUT_BLOBROOT");
                File expected = explicit ? new File("./explicit/") :
                    new File(sharedRoot != null && !sharedRoot.isBlank() ? sharedRoot : "./blob/");
                assertEquals(expected.getCanonicalFile(), BLOBManager.getBlobRoot().getCanonicalFile());
                assertTrue(BLOBManager.getBlobRoot().isDirectory());
                BLOBManager.initClient();
                assertEquals(expected.getCanonicalFile(), BLOBManager.getBlobRoot().getCanonicalFile());
                Files.writeString(Path.of(args[1]), BLOBManager.getBlobRoot().getCanonicalPath());
                return;
            }
            if ("invalid".equals(mode))
            {
                assertThrows(IllegalStateException.class, BLOBManager::initClient);
                assertFalse(BLOBManager.isInitialized());
                Files.writeString(Path.of(args[1]), System.getenv("ROUTEPUT_BLOBROOT"));
                return;
            }
            if ("null".equals(mode))
            {
                BLOBManager.init(null);
            } else {
                BLOBManager.initClient();
            }
            File root = BLOBManager.getBlobRoot();
            assertTrue(root.isDirectory());
            assertEquals(root.getAbsolutePath(), BLOBManager.settings.getString("blobStorageRoot"));
            BLOBManager.initClient();
            assertEquals(root, BLOBManager.getBlobRoot());
            if ("cached".equals(mode))
            {
                RoutePutChannel channel = RoutePutChannel.getChannel("shared");
                BLOBFile blob = BLOBManager.getBlob(null, channel, "cached.txt").get(1, TimeUnit.SECONDS);
                assertEquals(new File(root, "shared/cached.txt").getCanonicalFile(), blob.getCanonicalFile());
                assertEquals("already here", Files.readString(blob.toPath()));
            }
            if ("override".equals(mode))
            {
                Path explicit = Files.createDirectory(Path.of("explicit")).toAbsolutePath();
                BLOBManager.init(new JSONObject().put("blobStorageRoot", explicit.toString()).put("blobStorageTimeout", 0));
                assertEquals(explicit.toFile(), BLOBManager.getBlobRoot());
                BLOBManager.initClient();
                BLOBManager.init(null);
                assertEquals(explicit.toFile(), BLOBManager.getBlobRoot());
                BLOBManager.init(new JSONObject().put("blobStorageRoot", "ignored"));
                assertEquals(explicit.toFile(), BLOBManager.getBlobRoot());
            }
            Files.writeString(Path.of(args[1]), root.getAbsolutePath());
        }
    }
}
