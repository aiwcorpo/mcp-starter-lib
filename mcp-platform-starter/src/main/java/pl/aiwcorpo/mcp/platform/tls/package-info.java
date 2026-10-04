/**
 * TEMPLATE — TLS is configured DECLARATIVELY per server via Spring Boot SSL Bundles
 * (there is usually no Java to write). The platform's job here is to standardize the CONVENTION
 * and document it, so every server terminates TLS the same way while there is no gateway.
 *
 * <p>Platform convention (servers copy this into application-prod.yml, changing only the keystore
 * location/alias and port):
 * <pre>
 * spring:
 *   ssl:
 *     bundle:
 *       jks:
 *         mcp-server:
 *           key: { alias: ${MCP_TLS_KEY_ALIAS:mcp-server} }
 *           keystore:
 *             location: file:/etc/${server}/tls/keystore.p12
 *             password: ${MCP_TLS_KEYSTORE_PASSWORD}
 *             type: PKCS12
 * server:
 *   ssl:
 *     bundle: mcp-server
 *     enabled-protocols: TLSv1.3,TLSv1.2
 * </pre>
 *
 * <p>If you later add an MCP gateway, this convention (and the security auto-config) is where you
 * would centrally switch TLS termination off in the servers — one change in the platform, not N.
 */
package pl.aiwcorpo.mcp.platform.tls;
