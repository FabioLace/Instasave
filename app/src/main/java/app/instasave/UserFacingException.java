package app.instasave;

/** An error whose message is a string resource, so it can be shown in the user's language. */
final class UserFacingException extends Exception {
    final int messageRes;

    UserFacingException(int messageRes) { this(messageRes, null); }

    UserFacingException(int messageRes, Throwable cause) {
        super(cause);
        this.messageRes = messageRes;
    }
}
