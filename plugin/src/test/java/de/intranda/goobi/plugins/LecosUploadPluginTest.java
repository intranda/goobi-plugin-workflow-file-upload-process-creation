package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.List;

import org.apache.oro.text.perl.Perl5Util;
import org.junit.Test;

public class LecosUploadPluginTest {


    @Test public void testRegularExpression () {
        List<String> test = new ArrayList<>();
        test.add("abc.jpg");
        test.add("Stadtarchiv Crowdsourcing 06. Post BA_1991_336112.jpg");
        test.add("01. Post BA_1977_3699.jpg");
        test.add("Stadtarchiv Crowdsourcing 06. Post BA_1991_33611.jpg");
        test.add("BA_1988_26870.jpg");
        test.add("Stadtarchiv Crowdsourcing 06. Post BA_1991_336113.jpg");

        Perl5Util perlUtil = new Perl5Util();

        for (String s : test) {
            if (perlUtil.match("/(.*)_(\\d+)\\.jpg/", s)) {
                System.out.println("match");
                System.out.println(perlUtil.group(1));
                System.out.println(perlUtil.group(2));
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
