package com.xmission.trevin.android.crypto;

import javax.crypto.interfaces.PBEKey;
import javax.crypto.spec.PBEKeySpec;

public class BCPBEKey
    implements PBEKey
{
    private static final long serialVersionUID = 1;

    //
    // PBE Based encryption constants - we do PKCS5S2_UTF8 with SHA-256
    //
    static final int        SHA256       = 4;
    static final int        PKCS5S2_UTF8 = 5;

    String              algorithm;
    final int		type = PKCS5S2_UTF8;
    final int		digest = SHA256;
    int                 keySize;
    int                 ivSize;
    KeyParameter	param;
    PBEKeySpec          pbeKeySpec;
    boolean             tryWrong = false;

    /**
     * @param param
     */
    public BCPBEKey(
        String algorithm,
        int keySize,
        int ivSize,
        PBEKeySpec pbeKeySpec,
        KeyParameter param)
    {
        this.algorithm = algorithm;
        this.keySize = keySize;
        this.ivSize = ivSize;
        this.pbeKeySpec = pbeKeySpec;
        this.param = param;
    }

    public String getAlgorithm()
    {
        return algorithm;
    }

    public String getFormat()
    {
        return "RAW";
    }

    public byte[] getEncoded()
    {
        if (param != null)
        {
            return param.getKey();
        }
        else
        {
            return PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(pbeKeySpec.getPassword());
        }
    }
    
    int getType()
    {
        return type;
    }
    
    int getDigest()
    {
        return digest;
    }
    
    int getKeySize()
    {
        return keySize;
    }
    
    public int getIvSize()
    {
        return ivSize;
    }
    
    public KeyParameter getParam()
    {
        return param;
    }

    public char[] getPassword()
    {
        return pbeKeySpec.getPassword();
    }

    public byte[] getSalt()
    {
        return pbeKeySpec.getSalt();
    }

    public int getIterationCount()
    {
        return pbeKeySpec.getIterationCount();
    }
}
