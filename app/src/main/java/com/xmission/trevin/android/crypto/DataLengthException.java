package com.xmission.trevin.android.crypto;

/**
 * this exception is thrown if a buffer that is meant to have output
 * copied into it turns out to be too short, or if we've been given 
 * insufficient input. In general this exception will get thrown rather
 * than an ArrayOutOfBounds exception.
 */
public class DataLengthException 
    extends RuntimeCryptoException
{
    private static final long serialVersionUID = 1;

    /**
     * base constructor.
     */
    public DataLengthException()
    {
    }

    /**
     * create a DataLengthException with the given message.
     *
     * @param message the message to be carried with the exception.
     */
    public DataLengthException(String  message)
    {
        super(message);
    }

    /**
     * create a DataLengthException with the given cause.
     */
    public DataLengthException(Throwable t)
    {
	super(t);
    }

    /**
     * create a DataLengthException with the given message and cause.
     */
    public DataLengthException(String  message, Throwable t)
    {
	super(message, t);
    }
}
