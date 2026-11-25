package com.lyreo.assessment.api;

import com.lyreo.platform.content.AssetStore;
import com.lyreo.platform.content.CatalogPage;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/api/v1/admin/content-media")
public class ContentMediaController {
    private final AssetStore assets;
    public ContentMediaController(AssetStore assets) {this.assets=assets;}
    @GetMapping public CatalogPage<AssetStore.AssetSummary> list(
        @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="24") int size,
        @RequestParam(defaultValue="") String query,
        @RequestParam(defaultValue="") String kind
    ) {
        return assets.list(page,size,query,kind);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public AssetStore.Asset upload(@RequestParam MultipartFile file,@AuthenticationPrincipal Jwt jwt) throws java.io.IOException {
        return assets.upload(file.getOriginalFilename(),file.getBytes(),false,jwt.getSubject());
    }
    @GetMapping("/{id}") public AssetStore.Asset get(@PathVariable UUID id) {return assets.get(id);}
    @GetMapping("/{id}/file") public ResponseEntity<org.springframework.core.io.Resource> file(@PathVariable UUID id) {
        var asset=assets.get(id);var bytes=assets.bytes(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.mimeType())).cacheControl(CacheControl.noStore())
            .contentLength(bytes.length).header("X-Content-Type-Options","nosniff").body(new ByteArrayResource(bytes));
    }
}
