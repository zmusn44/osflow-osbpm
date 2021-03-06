package cn.linkey.domino;

import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.GregorianCalendar;

import cn.linkey.factory.BeanCtx;

public class LtpaLibrary {

    private static final byte[] ltpaTokenVersion = { 0, 1, 2, 3 };
    private static final int dateStringLength = 8;
    private static final String dateStringFiller = "00000000";
    private static final int creationDatePosition = ltpaTokenVersion.length;
    private static final int expirationDatePosition = creationDatePosition + dateStringLength;
    private static final int preUserDataLength = ltpaTokenVersion.length + dateStringLength + dateStringLength;
    private static final int hashLength = 20;

    /**
     * This method parses the LtpaToken cookie received from the web browser and returns the information in the <tt>TokenData</tt> class.
     * 
     * @param ltpaToken - the cookie (base64 encoded).
     * @param ltpaSecretStr - the contents of the <tt>LTPA_DominoSecret</tt> field from the SSO configuration document.
     * @return The contents of the cookie. If the cookie is invalid (the hash - or some other - test fails), this method returns <tt>null</tt>.
     * @throws NoSuchAlgorithmException
     * @throws Base64DecodeException
     */
    public static TokenData parseLtpaToken(String ltpaToken, String ltpaSecretStr) throws NoSuchAlgorithmException, Base64DecodeException {
        byte[] data = HttpUtils.base64Decode(ltpaToken);

        int variableLength = data.length - hashLength;
        /*
         * Compare to 20 to since variableLength must be at least (preUserDataLength + 1) [21] character long: Token version: 4 bytes Token creation: 8 bytes Token expiration: 8 bytes User name:
         * variable length > 0
         */
        if (variableLength <= preUserDataLength)
            return null;

        byte[] ltpaSecret = HttpUtils.base64Decode(ltpaSecretStr);

        if (!validateSHA(data, variableLength, ltpaSecret))
            return null;

        if (!compareBytes(data, 0, ltpaTokenVersion, 0, ltpaTokenVersion.length))
            return null;

        TokenData ret = new TokenData();
        ret.tokenCreated.setTimeInMillis((long) Integer.parseInt(getString(data, creationDatePosition, dateStringLength), 16) * 1000);
        ret.tokenExpiration.setTimeInMillis((long) Integer.parseInt(getString(data, expirationDatePosition, dateStringLength), 16) * 1000);

        byte[] nameBuffer = new byte[data.length - (preUserDataLength + hashLength)];
        System.arraycopy(data, preUserDataLength, nameBuffer, 0, variableLength - preUserDataLength);
        ret.username = new String(nameBuffer);

        return ret;
    }

    private static boolean validateSHA(byte[] ltpaTokenData, int variableLength, byte[] ltpaSecret) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");

        byte[] digestData = new byte[variableLength + ltpaSecret.length];

        System.arraycopy(ltpaTokenData, 0, digestData, 0, variableLength);
        System.arraycopy(ltpaSecret, 0, digestData, variableLength, ltpaSecret.length);

        byte[] digest = sha1.digest(digestData);

        if (digest.length > ltpaTokenData.length - variableLength)
            return false;

        int bytesToCompare = (digest.length <= ltpaTokenData.length - variableLength) ? digest.length : ltpaTokenData.length - variableLength;

        return compareBytes(digest, 0, ltpaTokenData, variableLength, bytesToCompare);
    }

    private static boolean compareBytes(byte[] b1, int offset1, byte[] b2, int offset2, int len) {
        if (len < 0)
            return false;
        // offset must be positive, and the compare chunk must be contained the buffer
        if ((offset1 < 0) || (offset1 + len > b1.length))
            return false;
        if ((offset2 < 0) || (offset2 + len > b2.length))
            return false;

        for (int i = 0; i < len; i++)
            if (b1[offset1 + i] != b2[offset2 + i])
                return false;

        return true;
    }

    /**
     * Convert bytes from the buffer into a String.
     * 
     * @param buffer - the buffer to take the bytes from.
     * @param offset - the offset in the buffer to start at.
     * @param len - the number of bytes to convert into a String.
     * @return - A String representation of specified bytes.
     */
    private static String getString(byte[] buffer, int offset, int len) {
        if (len < 0)
            return "";
        if ((offset + len) > buffer.length)
            return "";

        byte[] str = new byte[len];
        System.arraycopy(buffer, offset, str, 0, len);
        return new String(str);
    }

    //??????Domino?????????cookie
    public static String getcookie(String userid, int extTime) {
        try {
            return LtpaLibrary.createLtpaToken(userid, extTime, BeanCtx.getSystemConfig("DominoSecret"));
        }
        catch (Exception e) {
            e.printStackTrace();
            return "";
        }
        catch (Base64DecodeException e) {
            e.printStackTrace();
            return "";
        }
    }

    public static String createLtpaToken(String username, int durationMinutes, String ltpaSecretStr) throws NoSuchAlgorithmException, Base64DecodeException {
        return createLtpaToken(username, new GregorianCalendar(), durationMinutes, ltpaSecretStr);
    }

    public static String createLtpaToken(String username, GregorianCalendar creationTime, int durationMinutes, String ltpaSecretStr) throws NoSuchAlgorithmException, Base64DecodeException {
        // create byte array buffers for both strings
        byte[] ltpaSecret = HttpUtils.base64Decode(ltpaSecretStr);
        byte[] usernameArray = username.getBytes();

        byte[] workingBuffer = new byte[preUserDataLength + usernameArray.length + ltpaSecret.length];

        // copy version into workingBuffer
        System.arraycopy(ltpaTokenVersion, 0, workingBuffer, 0, ltpaTokenVersion.length);

        GregorianCalendar expirationDate = (GregorianCalendar) creationTime.clone();
        expirationDate.add(Calendar.MINUTE, durationMinutes);

        // copy creation date into workingBuffer 
        String hex = dateStringFiller + Integer.toHexString((int) (creationTime.getTimeInMillis() / 1000)).toUpperCase();
        System.arraycopy(hex.getBytes(), hex.getBytes().length - dateStringLength, workingBuffer, creationDatePosition, dateStringLength);

        // copy expiration date into workingBuffer 
        hex = dateStringFiller + Integer.toHexString((int) (expirationDate.getTimeInMillis() / 1000)).toUpperCase();
        System.arraycopy(hex.getBytes(), hex.getBytes().length - dateStringLength, workingBuffer, expirationDatePosition, dateStringLength);

        // copy user name into workingBuffer
        System.arraycopy(usernameArray, 0, workingBuffer, preUserDataLength, usernameArray.length);

        // copy the ltpaSecret into the workingBuffer
        System.arraycopy(ltpaSecret, 0, workingBuffer, preUserDataLength + usernameArray.length, ltpaSecret.length);

        byte[] hash = createHash(workingBuffer);

        // put the public data and the hash into the outputBuffer
        byte[] outputBuffer = new byte[preUserDataLength + usernameArray.length + hashLength];
        System.arraycopy(workingBuffer, 0, outputBuffer, 0, preUserDataLength + usernameArray.length);
        System.arraycopy(hash, 0, outputBuffer, preUserDataLength + usernameArray.length, hashLength);

        return HttpUtils.base64Encode(outputBuffer);
    }

    private static byte[] createHash(byte[] buffer) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        return sha1.digest(buffer);
    }

    //	private static boolean fieldContainsValue( Vector values, Item item ) {
    //	    for( int i = 0; i < values.size(); i++ ) {
    //	        if( item.containsValue( values.get( i ) ) ) return true;
    //	    }
    //	    return false;
    //	}

    /**
     * Get the contents of the LTPA_Secret field of the correct SSO configuration document based on the Internet site host name.
     * 
     * @param names - the <strong>names.nsf</strong> database.
     * @param siteName - the Internet host name of the site to generate/verify Domino cookie for.
     * @return A class containing LTPA data, <tt>null</tt> if no matching SSO configuration document was found.
     * @throws NotesException
     * @throws UnknownHostException
     */
    public static LtpaData getLtpaSecret() {

        String LTPA_DominoSecret = "";// domino??????
        int LTPA_TokenExpiration = 300; //??????
        String LTPA_TokenDomain = ""; //sso???
        LtpaData ret = new LtpaData(LTPA_DominoSecret, LTPA_TokenExpiration, LTPA_TokenDomain);

        return ret;
    }

}
