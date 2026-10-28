package pl.aiwcorpo.mcp.platform.resource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the identifier of a system resource.
 *
 * <p><b>On a parameter of a {@code @Tool} method</b> (a {@code String}, an integer, or a list or array of them): the
 * argument is a {@code resourceRefs} entry of the manifest (C6), and before the tool runs the platform resolves
 * <em>every</em> value of it to the resource as the system reports it (with {@link ResourceResolver}) and asks the
 * policy of the signed bundle whether the caller may do the tool's action on it (C7, {@link pl.aiwcorpo.mcp.platform.guard.ToolRisk#action()}). One
 * value that cannot be resolved, or is not allowed, ends the call with {@code not_found_or_denied}, the same answer
 * as for a resource that does not exist. A resource whose required classification attribute is missing (a Jira
 * project without {@code category}, a Confluence space without {@code labels}, an OpenSearch index without
 * {@code tags}) is not allowed either.
 *
 * <pre>{@code
 * @Tool(name = "get_issue", description = "Returns an issue by key.")
 * @ToolRisk(RiskLevel.READ_ONLY)
 * @ToolTitle("Get issue")
 * public IssueView getIssue(
 *         @ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey")
 *         @ResourceRef(type = "Jira::Issue", resolver = JiraIssueResolver.class) String issueKey) { ... }
 * }</pre>
 *
 * <p><b>On a component of a record the tool returns</b> (directly, or in a list or nested record): every element of a
 * list that has such a component is checked against the policy after the tool has run, and the elements the caller
 * may not see are removed from the result. Where the marked record is not an element of a list (the result itself or
 * a nested object), a denial ends the call with {@code not_found_or_denied}. The elements are resolved with the same
 * resolver as arguments, so a resource fetched for the argument check is not fetched again within the call.
 */
@Documented
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceRef {

    /** Cedar resource type of the contracts, for example {@code Jira::Issue}. */
    String type();

    /**
     * The resolver, a bean implementing {@link ResourceResolver}; {@code Void.class} (the default) means the only
     * resolver bean of {@link #type()}. A resolver of another type, or two resolvers of one type without an explicit
     * choice, stop the server.
     */
    Class<?> resolver() default Void.class;
}
