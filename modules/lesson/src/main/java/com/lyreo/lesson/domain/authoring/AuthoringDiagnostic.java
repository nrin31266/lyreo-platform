package com.lyreo.lesson.domain.authoring;
import java.util.*;
/** Only capability data enters this boundary; credentials and media never belong in diagnostics. */
public final class AuthoringDiagnostic {
 private AuthoringDiagnostic() {}
 public static Object clean(Object value) {
  if(value instanceof Map<?,?> map) {
   var result=new LinkedHashMap<String,Object>();
   map.forEach((key,item)->{String name=key.toString();if(!name.toLowerCase(Locale.ROOT).matches(".*(credential|authorization|password|secret|token|audio_base64|audio_url|headers).*")) result.put(name,clean(item));});return result;
  }
  if(value instanceof Collection<?> list) return list.stream().map(AuthoringDiagnostic::clean).toList();
  if(value instanceof String text) return text.replaceAll("https?://[^\\s\\\"<>]+","[URL removed]").replaceAll("(?i)(bearer|api[_ -]?key|password|secret)\\s*[:= ]\\s*\\S+","$1 [redacted]");
  return value;
 }
}
