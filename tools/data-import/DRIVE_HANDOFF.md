# Bàn giao gói dữ liệu qua Drive

Hai archive để upload nằm trong `.data/releases/`. Upload đúng hai file này vào thư mục Drive của nhóm.

| Archive | SHA-256 |
| --- | --- |
| `lexicon-2.0.1.tar.gz` | `ef183fa9cedc4bc2bd8f80b884116825f7735d3c7528acb30ceb9272a736972a` |
| `grammar-toeic-2.0.12.tar.gz` | `10b090b40e1b69fff690f99e79f5e09711c3eb0c7bd422031676c98c1b7226ba` |

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
- Từ checkout chưa có cache, chạy `make setup` rồi `make lexicon-fetch data-fetch` để kiểm link và checksum. Cài nội dung bằng `make setup-data DATASET=lexicon`, `make setup-data DATASET=grammar-toeic`, hoặc `make setup-data` để cài cả hai.
