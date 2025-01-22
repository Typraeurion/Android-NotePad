package com.xmission.trevin.android.crypto;

/**
 * Generator for PBE derived keys and ivs as defined by PKCS 5 V2.0 Scheme 2.
 * This generator uses a SHA-256 HMac as the calculation function.
 * <p>
 * The document this implementation is based on can be found at
 * <a href=http://www.rsasecurity.com/rsalabs/pkcs/pkcs-5/index.html>
 * RSA's PKCS5 Page</a>
 */
public class PKCS5S2ParametersGenerator
    extends PBEParametersGenerator
{
    private HMac hMac;
    private byte[] state;

    /**
     * construct a PKCS5 Scheme 2 Parameters generator.
     */
    public PKCS5S2ParametersGenerator()
    {
        hMac = new HMac(new SHA256Digest());
        state = new byte[hMac.getMacSize()];
    }

    private void F(
        byte[]  S,
        int     c,
        byte[]  iBuf,
        byte[]  out,
        int     outOff)
    {
        if (c == 0)
        {
            throw new IllegalArgumentException("iteration count must be at least 1.");
        }

        if (S != null)
        {
            hMac.update(S, 0, S.length);
        }

        hMac.update(iBuf, 0, iBuf.length);
        hMac.doFinal(state, 0);

        System.arraycopy(state, 0, out, outOff, state.length);

        for (int count = 1; count < c; count++)
        {
            hMac.update(state, 0, state.length);
            hMac.doFinal(state, 0);

            for (int j = 0; j != state.length; j++)
            {
                out[outOff + j] ^= state[j];
            }
        }
    }

    private byte[] generateDerivedKey(
        int dkLen)
    {
        int     hLen = hMac.getMacSize();
        int     l = (dkLen + hLen - 1) / hLen;
        byte[]  iBuf = new byte[4];
        byte[]  outBytes = new byte[l * hLen];
        int     outPos = 0;

        KeyParameter param = new KeyParameter(password);

        hMac.init(param);

        for (int i = 1; i <= l; i++)
        {
            // Increment the value in 'iBuf'
            int pos = 3;
            while (++iBuf[pos] == 0)
            {
                --pos;
            }

            F(salt, iterationCount, iBuf, outBytes, outPos);
            outPos += hLen;
        }

        return outBytes;
    }

    /**
     * Generate a key parameter derived from the password, salt, and iteration
     * count we are currently initialized with.
     *
     * @param keySize the size of the key we want (in bits)
     * @return a KeyParameter object.
     */
    public KeyParameter generateDerivedParameters(
        int keySize)
    {
        keySize = keySize / 8;

        byte[]  dKey = generateDerivedKey(keySize);

        return new KeyParameter(dKey, 0, keySize);
    }

    /**
     * Generate a key parameter for use with a MAC derived from the password,
     * salt, and iteration count we are currently initialized with.
     *
     * @param keySize the size of the key we want (in bits)
     * @return a KeyParameter object.
     */
    public KeyParameter generateDerivedMacParameters(
        int keySize)
    {
        return generateDerivedParameters(keySize);
    }
}
