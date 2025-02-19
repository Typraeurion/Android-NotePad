package com.xmission.trevin.android.crypto;

/**
 * the foundation class for the exceptions thrown by the crypto packages.
 */
public class RuntimeCryptoException 
    extends RuntimeException
{
    private static final long serialVersionUID = 1;

    /**
     * base constructor.
     */
    public RuntimeCryptoException()
    {
    }

    /**
     * create a RuntimeCryptoException with the given message.
     *
     * @param message the message to be carried with the exception.
     */
    public RuntimeCryptoException(String  message)
    {
        super(message);
    }

    /**
     * create a RuntimeCryptoException with the given cause.
     */
    public RuntimeCryptoException(Throwable t)
    {
	super(t);
    }

    /**
     * create a RuntimeCryptoException with the given message and cause.
     */
    public RuntimeCryptoException(String  message, Throwable t)
    {
	super(message, t);
    }
}
