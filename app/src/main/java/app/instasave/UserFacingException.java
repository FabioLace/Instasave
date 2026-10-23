package app.instasave;

import android.content.Context;

/** An error whose message is a string resource, so it can be shown in the user's language. */
final class UserFacingException extends Exception {
    final int messageRes;

    UserFacingException(int messageRes) { this(messageRes, null); }

    UserFacingException(int messageRes, Throwable cause) {
        super(cause);
        this.messageRes = messageRes;
    }

    /** The message to show for any failure, translated when the error carries a string resource. */
    static String messageFor(Context context, Exception error) {
        if (error instanceof UserFacingException) return context.getString(((UserFacingException) error).messageRes);
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? context.getString(R.string.error_generic) : message;
    }
}
