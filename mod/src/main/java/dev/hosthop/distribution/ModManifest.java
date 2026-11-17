package dev.hosthop.distribution;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;

/**
 * The signed list of mods the publisher distributes for one world. The world and revision are inside the signed
 * text, so a list cannot be replayed into another world or rolled back to an older, perhaps vulnerable, version.
 * bundle is the Drive file holding every JAR; it is null only when the list is empty.
 */
public record ModManifest(String world, long revision, String publisher, Bundle bundle, List<Entry> mods) {
    public static final int MAX_MODS = 64;
    public static final long MAX_JAR = 64L * 1024 * 1024;
    public static final long MAX_BUNDLE = 512L * 1024 * 1024;

    public record Bundle(String fileId, String sha256, long size) {}
    public record Entry(String id, String version, String file, String sha256, long size) {}

    public ModManifest {
        mods = List.copyOf(mods);
    }

    public String json() {
        var root = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("world", world);
        root.addProperty("revision", revision);
        root.addProperty("publisher", publisher);
        if (bundle != null) {
            var item = new JsonObject();
            item.addProperty("file_id", bundle.fileId());
            item.addProperty("sha256", bundle.sha256());
            item.addProperty("size", bundle.size());
            root.add("bundle", item);
        }
        var list = new JsonArray();
        for (var mod : mods) {
            var item = new JsonObject();
            item.addProperty("id", mod.id());
            item.addProperty("version", mod.version());
            item.addProperty("file", mod.file());
            item.addProperty("sha256", mod.sha256());
            item.addProperty("size", mod.size());
            list.add(item);
        }
        root.add("mods", list);
        return root.toString();
    }

    public static ModManifest parse(String text) throws IOException {
        if (text == null || text.length() > 60000) throw new IOException("Invalid mod list");
        try {
            var root = JsonParser.parseString(text).getAsJsonObject();
            if (root.get("format").getAsInt() != 1) throw new IOException("Unknown mod list format");
            Bundle bundle = null;
            if (root.has("bundle")) {
                var item = root.getAsJsonObject("bundle");
                bundle = new Bundle(item.get("file_id").getAsString(), item.get("sha256").getAsString(), item.get("size").getAsLong());
            }
            var mods = new java.util.ArrayList<Entry>();
            for (var element : root.getAsJsonArray("mods")) {
                var item = element.getAsJsonObject();
                mods.add(new Entry(item.get("id").getAsString(), item.get("version").getAsString(), item.get("file").getAsString(),
                        item.get("sha256").getAsString(), item.get("size").getAsLong()));
            }
            var manifest = new ModManifest(root.get("world").getAsString(), root.get("revision").getAsLong(),
                    root.get("publisher").getAsString(), bundle, mods);
            manifest.validate();
            return manifest;
        } catch (RuntimeException invalid) { throw new IOException("Invalid mod list", invalid); }
    }

    public void validate() throws IOException {
        if (world == null || !world.matches("[A-Za-z0-9_-]{1,80}") || revision < 1 || publisher == null || !publisher.matches("[A-Za-z0-9_-]{1,80}")
                || mods.size() > MAX_MODS || (bundle == null) != mods.isEmpty()) throw new IOException("Invalid mod list");
        if (bundle != null && (bundle.fileId() == null || !bundle.fileId().matches("[A-Za-z0-9_-]{1,200}") || !hash(bundle.sha256())
                || bundle.size() < 1 || bundle.size() > MAX_BUNDLE)) throw new IOException("Invalid mod bundle");
        var ids = new HashSet<String>();
        var hashes = new HashSet<String>();
        var files = new HashSet<String>();
        for (var mod : mods) {
            if (mod.id() == null || !mod.id().matches("[a-z][a-z0-9_]{1,63}") || mod.version() == null || mod.version().isEmpty()
                    || mod.version().length() > 64 || mod.version().chars().anyMatch(c -> c < 0x20 || c == 0x7f)
                    || mod.file() == null || !mod.file().matches("[A-Za-z0-9._+-]{1,120}\\.jar") || !hash(mod.sha256())
                    || mod.size() < 1 || mod.size() > MAX_JAR || !ids.add(mod.id()) || !hashes.add(mod.sha256()) || !files.add(mod.file()))
                throw new IOException("Invalid mod entry");
        }
    }

    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
