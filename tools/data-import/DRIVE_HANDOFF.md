# Bàn giao gói dữ liệu qua Drive

Hai archive dùng Drive nằm trong `.data/releases/`. Upload đúng hai file này vào thư mục Drive của nhóm.

| Archive | Role | Objects | SHA-256 | Drive Link |
| --- | --- | --- | --- | --- |
| `lexicon-2.0.1.tar.gz` | Full release | — | `ef183fa9cedc4bc2bd8f80b884116825f7735d3c7528acb30ceb9272a736972a` | [Drive](https://drive.google.com/file/d/1FcdLfF5cqmwl3U_et_jK-pHOcWW-cMCW/view) |
| `grammar-toeic-2.0.12.tar.gz` | Full release (rollback baseline) | — | `10b090b40e1b69fff690f99e79f5e09711c3eb0c7bd422031676c98c1b7226ba` | [Drive](https://drive.google.com/file/d/1ZbgXuQA33zsaNC9SBFMraTdtiCMtiyVd/view) |
| `grammar-toeic-catalog-2.0.12.tar.gz` | Catalog-only (`--catalog-only`) | — | `48ad50623e8565758084a0a3a8dfd3e046a255cde1936af404e4a4624d9c43a9` | [Drive](https://drive.google.com/file/d/1rNd1Xv8v70CBGC81evSu8HwXq6M6NIJx/view) |
| `grammar-toeic-media-2.0.12.tar.gz` | Media backup (`lyreo.media-backup`) | 4,875 | `df07a2650ad33f5d53ba504020f5abea61bb284f88c735614ad2b6bdc40bcf50` | [Drive](https://drive.google.com/file/d/1Z-d1rdfJkH6EUSm-QPeKU7bVlHU4ivse/view) |
| `vocabulary-catalog-1.3.0.tar.gz` | Catalog-only | — | `2fd037e2824c2663edcff72ff1ba48b958acb1d9953aa8e6935511a8551f2c51` | [Drive](https://drive.google.com/file/d/1PzxSDGzxihgs1gNW6FwvOuKI59bl2r0P/view) |
| `vocabulary-media-1.3.0.tar.gz` | R2 media backup (`lyreo.media-backup`) | 30,203 | `b59a02b92b4d0543abcf2b25f16e53f66059820cb55074200853cd62f6cb2464` | [Drive](https://drive.google.com/file/d/17O60vxpGbVgSPsVeA2mJcUgS98DwI3bd/view) |
| `release-checksums-manifest.json` | Release checksums index | — | `7ba47bde4a61982317fe760b718329342512b88cb9d919e058b60a6ac5094271` | [Drive](https://drive.google.com/file/d/1CXhomCiiC3b8PkzdoeE7oLjgn1LZhB6X/view) |

Thư mục Drive dùng chung: [Google Drive Release Folder](https://drive.google.com/drive/folders/1XrAmgAaSDtar8UAeKT9URq3KbRGN2UH4).

## Quy trình `--catalog-only` & Khôi phục Media Backup

- Cài/kiểm tra Grammar/TOEIC chỉ phần catalog (khi media đã có trên R2 hoặc `LOCAL_STORAGE_ROOT`): đặt `GRAMMAR_TOEIC_RELEASE_URL` và `GRAMMAR_TOEIC_RELEASE_SHA256` trỏ sang `grammar-toeic-catalog-2.0.12.tar.gz` rồi chạy `scripts/fetch_release.py --catalog-only` (hoặc đặt `GRAMMAR_TOEIC_CATALOG_ONLY=true`).
- Khôi phục media dự phòng từ `grammar-toeic-media-2.0.12.tar.gz` hoặc `vocabulary-media-1.3.0.tar.gz` lên storage đích: giải nén archive vào thư mục tạm rồi chạy `uv run --locked --extra dev python release_media.py --restore-media <EXTRACTED_DIR>` (có thể kiểm chéo thêm với `--release` khi có catalog).

Các thay đổi Admin và API dùng cùng hai archive. Giữ nguyên tên file và bytes để checksum khớp.
Mỗi archive chứa manifest, nội dung, nguồn và kiểm tra toàn vẹn. Dữ liệu thô, thư mục đã giải nén, log, bản dump DB và file `.env` nằm ngoài bộ bàn giao.

## Liên kết tải

Upload archive nguyên file trước. Dùng link Drive dạng `https://drive.google.com/file/d/FILE_ID/view`.
Nếu Drive giới hạn tải file lớn, dùng `tools/data-import/scripts/split_archive.py ARCHIVE DESTINATION`
để tạo các phần và một index `*.parts.json`. Upload các phần trước, sửa `url` của từng phần trong index
thành link Drive tương ứng, giữ nguyên thứ tự, tên, kích thước và hash của từng phần. Upload index sau cùng.
Khi dùng multipart, liên kết tải của gói trỏ vào index. SHA của gói luôn là SHA của archive nguyên file.

## Cấu hình và kiểm tra

Cập nhật `tools/data-import/.env.example` ở hai biến `LEXICON_RELEASE_URL` và `GRAMMAR_TOEIC_RELEASE_URL`
sau khi xác minh link mới. Với hai file trên, giữ nguyên `*_RELEASE_VERSION`, `*_RELEASE_SCHEMA_VERSION`,
`*_RELEASE_SHA256` và `*_RELEASE_DIR` đang khai báo. Máy đã có `tools/data-import/.env` cần cập nhật
hai URL tại đó vì `make init-env` giữ cấu hình local hiện có.

- Với Drive riêng tư, đặt `DATA_DRIVE_REMOTE` trong importer env thành tên remote rclone của tài khoản đã được cấp quyền. Để trống biến này khi dùng link có quyền đọc công khai.
- `DATABASE_URL` của importer và các biến datasource trong `apps/core-service/.env` phải cùng trỏ tới một DB. `make init-env` đồng bộ các giá trị dev từ Docker.
- Dùng R2 bằng cách đặt `STORAGE_MODE=r2` cùng `R2_ENDPOINT`, `R2_BUCKET`, `R2_REGION`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` trong hai env local. Với storage local, dùng `STORAGE_MODE=local` và cùng một `LOCAL_STORAGE_ROOT`.
- Từ checkout chưa có cache, chạy `make setup` rồi `make lexicon-fetch data-fetch` để kiểm link và checksum. Cài nội dung bằng `make setup-data DATASET=lexicon`, `make setup-data DATASET=grammar-toeic`, hoặc `make setup-data` để cài toàn bộ dữ liệu.


Vocabulary 1.3.0 dùng [archive danh mục trên Drive](https://drive.google.com/file/d/1PzxSDGzxihgs1gNW6FwvOuKI59bl2r0P/view)
cùng ảnh/audio dùng chung trên R2. SHA-256 của archive `1.3.0` là
`2fd037e2824c2663edcff72ff1ba48b958acb1d9953aa8e6935511a8551f2c51`.
Archive mới ở cùng thư mục với hai gói phía trên.
`make setup-data DATASET=vocabulary` ưu tiên archive local hợp lệ hoặc tự tải từ Drive và xác minh media trên R2.
Xem [hướng dẫn Vocabulary](README.md#vocabulary-word-atlas).
