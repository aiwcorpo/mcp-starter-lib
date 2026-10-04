package pl.aiwcorpo.mcp.platform.guard;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Holds the current {@link GuardPolicy} and reloads it when the file changes — no restart.
 *
 * <p>There is no watcher thread. Every {@link #current()} checks the file's modification time (at
 * most once per {@code checkIntervalMillis}), so a change takes effect on the very next tool call
 * and there is nothing to start, stop or leak.
 *
 * <h2>Failure behaviour</h2>
 * <ul>
 *   <li>Unreadable or invalid file <b>at startup</b>: the server does not start.</li>
 *   <li>Unreadable, empty or invalid file <b>on reload</b>: the last good policy stays in force and
 *       the error is logged. A typo while editing must not open the server up, and must not take it
 *       down either.</li>
 * </ul>
 *
 * <p>What this cannot detect is a file that was cut off at a point where it is still valid YAML.
 * Replace the file atomically (write a new file next to it, then {@code mv}) instead of saving
 * over it in place.
 */
public class GuardPolicyStore {

    private static final Logger LOG = LoggerFactory.getLogger(GuardPolicyStore.class);

    private final Resource resource;        // null = built-in default policy
    private final File file;                // non-null when the resource can be watched
    private final long checkIntervalMillis;
    private final LongSupplier clock;

    private volatile GuardPolicy policy;
    private volatile long lastCheckedAt;
    private volatile long loadedStamp;

    public GuardPolicyStore(Resource resource, long checkIntervalMillis) {
        this(resource, checkIntervalMillis, System::currentTimeMillis);
    }

    GuardPolicyStore(Resource resource, long checkIntervalMillis, LongSupplier clock) {
        this.resource = resource;
        this.checkIntervalMillis = checkIntervalMillis;
        this.clock = clock;
        this.file = watchableFile(resource);
        if (resource == null) {
            this.policy = GuardPolicy.DEFAULT;
            LOG.info("event=guard_policy_loaded source=built-in-default anonymous=read-only");
        } else {
            this.loadedStamp = stamp();
            this.policy = load();           // startup: let a bad file fail the context
            LOG.info("event=guard_policy_loaded source=\"{}\" hotReload={} roles={} toolRules={}",
                    resource.getDescription(), file != null, policy.roles().size(), policy.tools().size());
        }
        this.lastCheckedAt = clock.getAsLong();
    }

    /** The policy to apply right now. Cheap enough to call on every tool invocation. */
    public GuardPolicy current() {
        if (file != null) {
            long now = clock.getAsLong();
            if (now - lastCheckedAt >= checkIntervalMillis) {
                reloadIfChanged(now);
            }
        }
        return policy;
    }

    private synchronized void reloadIfChanged(long now) {
        if (now - lastCheckedAt < checkIntervalMillis) {
            return;   // another thread just did it
        }
        lastCheckedAt = now;
        long stamp = stamp();
        if (stamp == loadedStamp) {
            return;
        }
        loadedStamp = stamp;   // remember even on failure, so a broken file is reported once, not per call
        try {
            policy = load();
            LOG.warn("event=guard_policy_reloaded source=\"{}\" roles={} toolRules={}",
                    resource.getDescription(), policy.roles().size(), policy.tools().size());
        } catch (RuntimeException ex) {
            LOG.error("event=guard_policy_reload_failed source=\"{}\" action=keeping_previous_policy reason=\"{}\"",
                    resource.getDescription(), ex.getMessage());
        }
    }

    private GuardPolicy load() {
        try (InputStream in = file != null ? Files.newInputStream(file.toPath()) : resource.getInputStream()) {
            // SafeConstructor: plain maps, lists and scalars only — a policy file never instantiates classes.
            Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            return GuardPolicy.fromYaml(document);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read guard policy " + resource.getDescription()
                    + " (working directory: " + new File("").getAbsolutePath() + ")", ex);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid guard policy " + resource.getDescription() + ": " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Malformed YAML in guard policy " + resource.getDescription(), ex);
        }
    }

    /** Modification time plus length: catches an edit made within the same timestamp tick. */
    private long stamp() {
        return file == null ? 0L : file.lastModified() * 31 + file.length();
    }

    private static File watchableFile(Resource resource) {
        if (resource == null || !resource.isFile()) {
            return null;   // e.g. a policy packed inside a jar: loaded once
        }
        try {
            return resource.getFile();
        } catch (IOException ex) {
            return null;
        }
    }
}
