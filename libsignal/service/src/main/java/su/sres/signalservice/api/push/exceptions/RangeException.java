/**
 * Copyright (C) 2014-2016 Open Whisper Systems
 * <p>
 * Licensed according to the LICENSE file in this repository.
 */

package su.sres.signalservice.api.push.exceptions;

public final class RangeException extends NonSuccessfulResponseCodeException {

    public RangeException(long requested) {
        super(416, "Range request out of bounds " + requested);
    }
}
