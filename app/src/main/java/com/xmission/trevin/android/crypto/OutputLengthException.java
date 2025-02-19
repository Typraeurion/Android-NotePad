package com.xmission.trevin.android.crypto;

public class OutputLengthException
    extends DataLengthException
{
    private static final long serialVersionUID = 1;

    public OutputLengthException(String msg)
    {
        super(msg);
    }

    public OutputLengthException(String msg, Throwable t)
    {
	super(msg, t);
    }
}
