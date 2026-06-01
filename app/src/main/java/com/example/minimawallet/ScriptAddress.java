package com.example.minimawallet;

import org.minima.objects.Address;

/**
 * Local replacement for the old "newscript script:... trackall:false" round-trip.
 * MEG has no built-in endpoint for it, but Minima script addresses are deterministic,
 * so we compute them from minima.jar primitives instead of asking a server.
 */
public final class ScriptAddress {

    private ScriptAddress() {}

    /** Returns the 0x-prefixed hex address of the script. */
    public static String hex(String script) {
        return new Address(script).getAddressData().to0xString();
    }

    /** Returns the Mx... mini-address form. */
    public static String mini(String script) {
        return new Address(script).getMinimaAddress();
    }
}
