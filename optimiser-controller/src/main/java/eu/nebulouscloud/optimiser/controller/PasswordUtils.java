package eu.nebulouscloud.optimiser.controller;
import java.security.SecureRandom;

public class PasswordUtils {
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";	
    /**
     * Generate a random password with alphanumerical characters (upper and lower case) of the given length
     * @param length
     * @return
     */
    public static String generatePassword(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            int index = random.nextInt(CHARACTERS.length());
            sb.append(CHARACTERS.charAt(index));
        }

        return sb.toString();
    }  
    
}
