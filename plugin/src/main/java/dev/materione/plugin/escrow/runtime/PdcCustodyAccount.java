package dev.materione.plugin.escrow.runtime;

import java.util.function.Consumer;

/** A keyed or stack custody account backed by a persistent-data byte array. */
public final class PdcCustodyAccount extends CustodyAccount {

    public PdcCustodyAccount(Consumer<byte[]> persist) {
        super(persist);
    }

    @Override
    public boolean parksOnClose() {
        return true;
    }
}
