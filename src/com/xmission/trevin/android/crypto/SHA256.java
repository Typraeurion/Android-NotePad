package com.xmission.trevin.android.crypto;

import java.security.MessageDigest;

public class SHA256
{
    private SHA256()
    {

    }

    static public class Digest
        extends MessageDigest
        implements Cloneable
    {
        protected SHA256Digest digest = new SHA256Digest();

        public Digest()
        {
            super(SHA256Digest.ALGORITHM_NAME);
        }

        public void engineReset() 
        {
            digest.reset();
        }

        public void engineUpdate(
        	byte    input) 
        {
            digest.update(input);
        }

        public void engineUpdate(
        	byte[]  input,
        	int     offset,
        	int     len) 
        {
            digest.update(input, offset, len);
        }

        public byte[] engineDigest() 
        {
            byte[]  digestBytes = new byte[digest.getDigestSize()];

            digest.doFinal(digestBytes, 0);

            return digestBytes;
        }

        public Object clone()
            throws CloneNotSupportedException
        {
            Digest d = (Digest)super.clone();
            d.digest = new SHA256Digest((SHA256Digest)digest);

            return d;
        }
    }
}
