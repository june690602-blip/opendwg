# CleanCAD Viewer — Play Store 출시 가이드 (사용자 액션)

> 레포/빌드 준비(R8, 아이콘, 개인정보처리방침, 스토어 텍스트, 스크린샷)는 완료됨.
> 아래는 **본인만 할 수 있는 단계**(키스토어 비밀번호·콘솔 업로드)다.

## 1. 업로드 키스토어 생성 (한 번만)
비밀번호는 **본인만 알고**, 안전한 곳에 **백업**한다(분실 시 앱 업데이트 불가).
프로젝트 루트(`C:\dev\opendwg`)에서:

```bash
keytool -genkeypair -v -keystore cleancad-release.jks -alias cleancad \
  -keyalg RSA -keysize 2048 -validity 9125 \
  -dname "CN=CleanCAD, O=june690602-blip, C=KR"
```
- 실행 시 키스토어 비밀번호를 직접 입력한다.
- `cleancad-release.jks` 는 `.gitignore` 로 이미 제외되어 커밋되지 않는다.
- 별도 백업(클라우드/USB) 필수.

## 2. 릴리즈 AAB 빌드
환경변수에 본인 키스토어 정보를 넣고 빌드(비밀번호는 화면에 남지 않게 주의):

```bash
KEYSTORE_PATH=../cleancad-release.jks \
KEYSTORE_PASS=<키스토어비번> KEY_ALIAS=cleancad KEY_PASS=<키비번> \
  ./gradlew :app:bundleRelease
```
- 산출물: `app/build/outputs/bundle/release/app-release.aab`
- (검증됨: R8 minify·16KB 정렬·3개 ABI·디버그심볼 포함, 빈 도면 렌더 정상)

## 3. Play Console 업로드
1. Play Console → 앱 만들기 → 이름 **CleanCAD Viewer**, 무료, 카테고리 **도구**
2. **Play 앱 서명** 사용(권장): 위 키스토어는 *업로드 키*, 실제 서명키는 Google 보관
3. **내부 테스트(Internal testing)** 트랙에 `app-release.aab` 업로드 → 본인 기기로 설치 검증
4. 검증 OK 후 비공개/프로덕션으로 승급

## 4. 스토어 등록정보 입력
- 설명/카테고리: `docs/store/listing.md` 초안 사용
- 아이콘(512): `docs/store/icon-512.png`
- 스크린샷: `docs/store/screens/` (폰에서 확대 화면 추가 촬영 권장)
- 기능 그래픽(1024×500): 별도 제작 필요
- **개인정보처리방침 URL**: 5번 GitHub Pages 활성화 후 입력
- **데이터 안전성**: 수집/공유 없음(폼 답안은 listing.md 참고)
- **콘텐츠 등급**: 설문에서 모두 "없음" → 전체이용가

## 5. GitHub Pages (개인정보처리방침 호스팅)
1. GitHub repo `june690602-blip/opendwg` → Settings → Pages
2. Source: Deploy from a branch → Branch `main` / 폴더 `/docs` → Save
3. 공개 URL: `https://june690602-blip.github.io/opendwg/privacy.html`
4. 이 URL 을 콘솔 "개인정보처리방침"에 입력

## 6. GPL v3 메모
- 본인이 단독 저작권자라 Play 배포 가능. **GitHub 소스 공개 유지**(다운스트림 GPL 권리).
- About 화면의 LibreDWG/GPL 고지 유지. 스토어 설명에 소스 링크 권장.

## 체크리스트
- [ ] 키스토어 생성 + 백업
- [ ] AAB 빌드
- [ ] GitHub Pages 활성화 → privacy URL 확보
- [ ] 콘솔 앱 생성 + 내부테스트 업로드 + 본인 기기 검증
- [ ] 스토어 등록정보/그래픽/데이터안전성/등급 입력
- [ ] 프로덕션 출시 신청
