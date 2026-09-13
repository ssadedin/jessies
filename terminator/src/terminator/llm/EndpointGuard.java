package terminator.llm;

import java.net.*;
import java.util.*;
import org.jessies.test.*;

/**
 * Stops terminal contents being sent to an endpoint outside local and private networks unless the
 * user has explicitly allowed it. See section 7 of the plan.
 *
 * This guards against configuration mistakes (pasting a cloud URL), not against a hostile local
 * DNS server: HttpClient resolves the host again when it connects.
 */
public final class EndpointGuard {
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private EndpointGuard() {
    }

    /**
     * Returns a description of why requests to the endpoint aren't allowed, or empty if they are.
     */
    public static Optional<String> check(String endpoint, boolean allowNonLocalEndpoint) {
        return check(endpoint, allowNonLocalEndpoint, InetAddress::getAllByName);
    }

    static Optional<String> check(String endpoint, boolean allowNonLocalEndpoint, Resolver resolver) {
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException ex) {
            return Optional.of("The LLM endpoint \"" + endpoint + "\" isn't a valid URL.");
        }
        String scheme = Objects.requireNonNullElse(uri.getScheme(), "").toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.of("The LLM endpoint \"" + endpoint + "\" must be an http or https URL.");
        }
        if (uri.getHost() == null) {
            return Optional.of("The LLM endpoint \"" + endpoint + "\" has no host.");
        }
        if (allowNonLocalEndpoint) {
            return Optional.empty();
        }
        // URI.getHost keeps the brackets around IPv6 literals.
        String host = uri.getHost().replaceAll("^\\[|\\]$", "");
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException ex) {
            return Optional.of("Couldn't resolve the LLM endpoint host \"" + host + "\".");
        }
        for (InetAddress address : addresses) {
            if (!isLocalOrPrivate(address)) {
                return Optional.of("The LLM endpoint host \"" + host + "\" resolves to " + address.getHostAddress() + ", which isn't on a local or private network. Terminal contents are only sent to local and private networks unless \"Allow endpoints outside local and private networks\" is turned on in Preferences.");
            }
        }
        return Optional.empty();
    }

    static boolean isLocalOrPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            // Site-local covers 10/8, 172.16/12 and 192.168/16 for IPv4.
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            // Carrier-grade NAT, 100.64/10, which Tailscale uses.
            return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
        }
        // IPv6 unique local addresses, fc00::/7.
        return (bytes[0] & 0xfe) == 0xfc;
    }

    private static InetAddress address(String literal) {
        try {
            // A literal never needs a DNS lookup.
            return InetAddress.getByName(literal);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException(literal, ex);
        }
    }

    private static Resolver fakeResolver(String... literals) {
        return host -> {
            if (literals.length == 0) {
                throw new UnknownHostException(host);
            }
            return Arrays.stream(literals).map(EndpointGuard::address).toArray(InetAddress[]::new);
        };
    }

    @Test private static void testLocalAndPrivateAddresses() {
        for (String literal : List.of("127.0.0.1", "::1", "0.0.0.0", "10.1.2.3", "172.20.0.1", "192.168.1.10", "169.254.1.1", "100.64.0.1", "100.127.255.254", "fd12:3456::1", "fe80::1")) {
            Assert.equals(literal + " " + isLocalOrPrivate(address(literal)), literal + " true");
        }
        for (String literal : List.of("8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:4860:4860::8888")) {
            Assert.equals(literal + " " + isLocalOrPrivate(address(literal)), literal + " false");
        }
    }

    @Test private static void testCheck() {
        Assert.equals(check("http://localhost:11434/v1", false, fakeResolver("127.0.0.1", "::1")), Optional.empty());
        Assert.equals(check("http://[::1]:8080/v1", false, host -> new InetAddress[] { address(host) }), Optional.empty());
        Assert.equals(check("https://gpu-box.lan/v1", false, fakeResolver("192.168.1.20")), Optional.empty());

        Assert.contains(check("https://api.example.com/v1", false, fakeResolver("93.184.216.34")).orElse(""), "isn't on a local or private network");
        // Every address must be local, not just the first.
        Assert.contains(check("http://mixed.example/v1", false, fakeResolver("10.0.0.1", "93.184.216.34")).orElse(""), "93.184.216.34");
        Assert.contains(check("http://nowhere.invalid/v1", false, fakeResolver()).orElse(""), "Couldn't resolve");

        // Allowed explicitly: no lookup needed.
        Assert.equals(check("https://api.example.com/v1", true, fakeResolver()), Optional.empty());

        Assert.contains(check("ftp://localhost/v1", true, fakeResolver()).orElse(""), "http or https");
        Assert.contains(check("localhost:11434", true, fakeResolver()).orElse(""), "http or https");
        Assert.contains(check("http:///v1", true, fakeResolver()).orElse(""), "no host");
        Assert.contains(check("http://bad host/", true, fakeResolver()).orElse(""), "isn't a valid URL");
    }
}
