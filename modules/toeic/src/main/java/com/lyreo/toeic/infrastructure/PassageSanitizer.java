package com.lyreo.toeic.infrastructure;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;
import com.lyreo.toeic.application.port.PassageCleaner;

@Component
public class PassageSanitizer implements PassageCleaner {
    private static final Safelist ALLOWED=new Safelist().addTags("div","p","pre","span","strong","b","em","i","u","br","hr","h1","h2","h3","h4","small","ul","ol","li","table","thead","tbody","tfoot","tr","th","td","colgroup","col","sup","sub","blockquote")
        .addAttributes("td","colspan","rowspan").addAttributes("th","colspan","rowspan").addAttributes("col","span").addAttributes(":all","style");
    public String clean(String html) {
        var document=Jsoup.parseBodyFragment(Jsoup.clean(html==null?"":html,ALLOWED));
        for(var element:document.select("[style]")) {
            var safe=new java.util.ArrayList<String>();
            for(var declaration:element.attr("style").split(";")) {
                String value=declaration.trim().toLowerCase(java.util.Locale.ROOT);
                if(value.matches("text-align\\s*:\\s*(left|right|center|justify)") || value.matches("white-space\\s*:\\s*(pre-wrap|normal)")) safe.add(value);
            }
            if(safe.isEmpty()) element.removeAttr("style");else element.attr("style",String.join(";",safe));
        }
        return document.body().html();
    }
    public boolean hasContent(String html) {
        return !Jsoup.parseBodyFragment(clean(html)).text().replace('\u00a0',' ').isBlank();
    }
}
