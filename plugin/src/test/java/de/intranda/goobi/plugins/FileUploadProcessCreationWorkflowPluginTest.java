package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

public class FileUploadProcessCreationWorkflowPluginTest {

    @Test
    public void testRegularExpression() {
        List<String> test = new ArrayList<>();
        test.add("abc.jpg");
        test.add("Stadtarchiv Crowdsourcing 06. Post BA_1991_336112.jpg");
        test.add("01. Post BA_1977_3699.jpg");
        test.add("Stadtarchiv Crowdsourcing 06. Post BA_1991_33611.jpg");
        test.add("BA_1988_26870.jpg");
        test.add("Stadtarchiv Crowdsourcing 06. Post BA_1991_336113.jpg");

        Pattern pattern = Pattern.compile("(.*)_(\\d+)\\.jpg");
        for (String s : test) {
            Matcher matcher = pattern.matcher(s);
            if (matcher.matches()) {

                System.out.println("match");
                System.out.println(matcher.group(1));
                System.out.println(matcher.group(2));
            }
            //            Matcher m =pattern.matcher(s);
            //            System.out.println(m.group());
            //            String processTitle =  m.group(1);
            //            String imageName =  m.group(2);
            //            System.out.println("title:  " +processTitle);
            //            System.out.println("image:  " +imageName);

        }
    }
}
