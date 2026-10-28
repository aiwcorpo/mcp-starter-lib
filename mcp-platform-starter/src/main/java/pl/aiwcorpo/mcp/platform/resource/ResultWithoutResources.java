package pl.aiwcorpo.mcp.platform.resource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a {@code @Tool} method whose result has a part the platform cannot follow ({@code Map}, {@code Object},
 * {@code JsonNode}, a class that is not a record): the result holds no resources that must be filtered, or the tool
 * filters them itself with {@link ResourcePolicy#retainAllowed} before it returns. Without the mark such a result stops the
 * server, because the platform cannot tell whether it hands the model a resource the caller may not see.
 *
 * <p>Not needed for scalars, records without {@link ResourceRef} components and lists of them. It is an error on a method
 * whose result type holds a {@link ResourceRef} component (the platform filters it).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResultWithoutResources {
}
