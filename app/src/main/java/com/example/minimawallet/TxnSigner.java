package com.example.minimawallet;

import android.util.Log;

import org.minima.database.userprefs.txndb.TxnRow;
import org.minima.objects.Transaction;
import org.minima.objects.Witness;
import org.minima.objects.base.MiniData;
import org.minima.objects.keys.Signature;
import org.minima.objects.keys.TreeKey;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.SecureRandom;

/**
 * Local signer for MEG unsigned-tx hex blobs. Replaces the server-side
 * txnsign/txnpost round-trips. Used by KeyGenerator and the fragments
 * that build raw transactions.
 */
public final class TxnSigner {

    private static final String TAG = "TxnSigner";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TxnSigner() {}

    /** Picks a fresh key-use index and signs the unsigned hex. Returns signed hex or null on failure. */
    public static String sign(String unsignedHex, TreeKey treekey) {
        if (unsignedHex == null || treekey == null) return null;
        try {
            int use = RANDOM.nextInt(Math.max(1, treekey.getMaxUses()));
            treekey.setUses(use);

            MiniData miniData = new MiniData(unsignedHex);
            TxnRow unsigned = TxnRow.convertMiniDataVersion(miniData);
            if (unsigned == null) return null;

            Witness witness = unsigned.getWitness();
            Transaction tx = unsigned.getTransaction();
            MiniData txid = tx.getTransactionID();

            witness.clearSignatures();
            Signature signature = treekey.sign(txid);
            witness.addSignature(signature);

            TxnRow signed = new TxnRow(unsigned.getID(), tx, witness);
            return serialize(signed);
        } catch (Exception e) {
            Log.e(TAG, "sign error: " + e.getMessage());
            return null;
        }
    }

    private static String serialize(TxnRow row) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            row.writeDataStream(dos);
            dos.flush();
            String hex = new MiniData(baos.toByteArray()).to0xString();
            return hex.length() >= 2 ? hex.substring(2) : hex;
        } catch (Exception e) {
            Log.e(TAG, "serialize error: " + e.getMessage());
            return null;
        }
    }
}
