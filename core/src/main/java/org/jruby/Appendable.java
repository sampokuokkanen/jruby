package org.jruby;

import org.jcodings.Encoding;
import org.jcodings.specific.USASCIIEncoding;

public interface Appendable {
    void appendIntoString(RubyString target);

    /**
     * @return the encoding of the bytes {@link #appendIntoString(RubyString)} appends. US-ASCII unless overridden,
     * as for the to_s of Integer, Float, true and false.
     */
    default Encoding getEncoding() {
        return USASCIIEncoding.INSTANCE;
    }
}
