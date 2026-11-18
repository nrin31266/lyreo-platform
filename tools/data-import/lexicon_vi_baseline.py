"""Import Vietnamese-edition senses as source-owned senses, never matched to English senses."""
import hashlib
import uuid
from lexicon_pronunciation import accent, pronunciation_metadata


def source_id(checksum, line, kind, ordinal=0):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"lyreo:lexicon:vi:{checksum}:{line}:{kind}:{ordinal}"))


def stage_record(conn, record, line, checksum, entry_id, normalize, lookup, encode):
    word = normalize(str(record.get("word") or ""))
    if not word:
        return None, {}
    conn.execute("INSERT OR IGNORE INTO entries VALUES (?,?,?,?,?,?,?)", (entry_id,word,word,lookup(word),"PHRASE" if " " in word else "WORD","en",2 if " " in word else 1))
    item_id = source_id(checksum,line,"item")
    pos = record.get("pos") or "unknown"
    context = {"source":"viwiktionary","source_sha256":checksum,"record_line":line}
    payload = {"id":item_id,"entry_id":entry_id,"pos":pos,"pos_title":record.get("pos_title") or pos,
        "etymology_number":record.get("etymology_number"),"etymology_text":"\n".join(record.get("etymology_texts") or []) or None,
        "order_index":-line,"source_context":context}
    conn.execute("INSERT INTO items VALUES (?,?,?,?,?,?)", (item_id,entry_id,pos,payload["etymology_number"],-line,encode(payload)))
    senses = {}
    for ordinal, sense in enumerate(record.get("senses") or [],1):
        glosses = [normalize(g) for g in sense.get("glosses") or [] if normalize(g)]
        if not glosses:
            continue
        sid = source_id(checksum,line,"sense",ordinal)
        text = "\n".join(glosses)
        payload = {"id":sid,"entry_id":entry_id,"item_id":item_id,"ordinal":ordinal,
            "definition_en":None,"translation_vi":text,"translation_status":"AVAILABLE",
            "definitions":{"vi":text},"source_context":{**context,"sense_ordinal":ordinal},
            "raw_glosses":sense.get("glosses") or [],"examples":sense.get("examples") or [],
            "tags":sense.get("tags") or [],"matched_qualifier":None}
        conn.execute("INSERT INTO senses VALUES (?,?,?,?,?,?,?,?)",(sid,entry_id,item_id,ordinal,None,text,"AVAILABLE",encode(payload)))
        senses[ordinal]=sid
    for ordinal, form in enumerate(record.get("forms") or [],1):
        text=normalize(str(form.get("form") or ""))
        if not text or text=="-": continue
        fid=source_id(checksum,line,"form",ordinal)
        payload={"id":fid,"entry_id":entry_id,"item_id":item_id,"form":text,"normalized_form":lookup(text),"tags":form.get("tags") or [],"source_context":context}
        conn.execute("INSERT INTO forms VALUES (?,?,?,?,?,?)",(fid,entry_id,item_id,text,lookup(text),encode(payload)))
    for ordinal, sound in enumerate(record.get("sounds") or [],1):
        notation=sound.get("ipa"); audio=sound.get("mp3_url") or sound.get("ogg_url"); audio_file=sound.get("audio")
        if not (notation or audio or audio_file): continue
        pid=source_id(checksum,line,"sound",ordinal)
        details=pronunciation_metadata(sound,form=word,source="viwiktionary")
        payload={"id":pid,"entry_id":entry_id,"item_id":item_id,"accent":accent(sound),"ipa":notation,
            "audio_url":audio,"audio_file":audio_file,"source_url":sound.get("ogg_url") or audio,
            "tags":details["labels"],"pronunciation_context":details,"source_context":context}
        conn.execute("INSERT INTO pronunciations VALUES (?,?,?,?,?,?,?)",(pid,entry_id,item_id,accent(sound),notation,audio,encode(payload)))
    return item_id,senses
