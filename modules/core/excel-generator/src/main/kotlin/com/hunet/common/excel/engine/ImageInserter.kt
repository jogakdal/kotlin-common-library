package com.hunet.common.excel.engine

import com.hunet.common.excel.detectImageTypeForPoi
import org.apache.poi.ss.usermodel.ClientAnchor
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFClientAnchor
import org.apache.poi.xssf.usermodel.XSSFDrawing
import org.apache.poi.xssf.usermodel.XSSFSheet

/**
 * 이미지 삽입기 - 셀 위치에 이미지 삽입
 */
class ImageInserter {

    companion object {
        // POI 이미지 타입 상수
        private val IMAGE_TYPE_MAP = mapOf(
            "PNG" to Workbook.PICTURE_TYPE_PNG,
            "JPEG" to Workbook.PICTURE_TYPE_JPEG,
            "JPG" to Workbook.PICTURE_TYPE_JPEG,
            "DIB" to Workbook.PICTURE_TYPE_DIB,
            "EMF" to Workbook.PICTURE_TYPE_EMF,
            "WMF" to Workbook.PICTURE_TYPE_WMF
        )
    }

    /**
     * 셀 위치에 이미지 삽입
     *
     * 이미지는 항상 지정된 영역(셀 또는 병합 영역)에 맞춰 크기가 조정됩니다.
     * 원본 크기로 삽입하려면 insertImageWithOriginalSize()를 사용하세요.
     *
     * @param workbook 워크북
     * @param sheet 시트
     * @param imageBytes 이미지 바이트 배열
     * @param rowIndex 시작 행 (0-based)
     * @param colIndex 시작 열 (0-based)
     * @param mergedRegion 병합 영역 (있으면 병합 영역에 맞춤, 없으면 단일 셀에 맞춤)
     */
    fun insertImage(
        workbook: Workbook,
        sheet: Sheet,
        imageBytes: ByteArray,
        rowIndex: Int,
        colIndex: Int,
        mergedRegion: CellRangeAddress? = null
    ) {
        // 이미지 타입 감지
        val imageTypeStr = imageBytes.detectImageTypeForPoi()
        val pictureType = IMAGE_TYPE_MAP[imageTypeStr]
            ?: throw IllegalArgumentException("지원하지 않는 이미지 형식: $imageTypeStr")

        // 워크북에 이미지 추가
        val pictureIdx = workbook.addPicture(imageBytes, pictureType)

        // 그리기 도형 생성
        val drawing = sheet.createDrawingPatriarch()

        // 앵커 설정 (이미지 위치) - 이미지가 지정 영역에 맞춰짐
        val anchor = createAnchor(workbook, rowIndex, colIndex, mergedRegion)

        // 이미지 삽입 (resize() 호출 안 함 - 앵커 영역에 맞춰짐)
        drawing.createPicture(anchor, pictureIdx)
    }

    /**
     * 셀 위치에 이미지를 원본 크기로 삽입
     *
     * @param workbook 워크북
     * @param sheet 시트
     * @param imageBytes 이미지 바이트 배열
     * @param rowIndex 시작 행 (0-based)
     * @param colIndex 시작 열 (0-based)
     */
    fun insertImageWithOriginalSize(
        workbook: Workbook,
        sheet: Sheet,
        imageBytes: ByteArray,
        rowIndex: Int,
        colIndex: Int
    ) {
        val imageTypeStr = imageBytes.detectImageTypeForPoi()
        val pictureType = IMAGE_TYPE_MAP[imageTypeStr]
            ?: throw IllegalArgumentException("지원하지 않는 이미지 형식: $imageTypeStr")

        val pictureIdx = workbook.addPicture(imageBytes, pictureType)
        val drawing = sheet.createDrawingPatriarch()
        val anchor = createAnchor(workbook, rowIndex, colIndex, null)
        val picture = drawing.createPicture(anchor, pictureIdx)

        // 원본 크기로 리사이즈
        picture.resize()
    }

    /**
     * XSSF 전용 이미지 삽입 (앵커 미세 조정 가능)
     */
    fun insertImageXssf(
        workbook: Workbook,
        sheet: XSSFSheet,
        imageBytes: ByteArray,
        rowIndex: Int,
        colIndex: Int,
        mergedRegion: CellRangeAddress? = null,
        marginPx: Int = 5
    ) {
        val imageTypeStr = imageBytes.detectImageTypeForPoi()
        val pictureType = IMAGE_TYPE_MAP[imageTypeStr]
            ?: throw IllegalArgumentException("지원하지 않는 이미지 형식: $imageTypeStr")

        val pictureIdx = workbook.addPicture(imageBytes, pictureType)
        val drawing = sheet.createDrawingPatriarch() as XSSFDrawing

        // 앵커 생성 (마진 포함)
        val anchor = createXssfAnchor(
            rowIndex, colIndex, mergedRegion, marginPx
        )

        drawing.createPicture(anchor, pictureIdx)
    }

    /**
     * 이미지 앵커 생성
     */
    private fun createAnchor(
        workbook: Workbook,
        rowIndex: Int,
        colIndex: Int,
        mergedRegion: CellRangeAddress?
    ): ClientAnchor {
        val helper = workbook.creationHelper

        return if (mergedRegion != null) {
            helper.createClientAnchor().also { anchor ->
                anchor.setCol1(mergedRegion.firstColumn)
                anchor.setRow1(mergedRegion.firstRow)
                anchor.setCol2(mergedRegion.lastColumn + 1)
                anchor.setRow2(mergedRegion.lastRow + 1)
                anchor.anchorType = ClientAnchor.AnchorType.MOVE_AND_RESIZE
            }
        } else {
            helper.createClientAnchor().also { anchor ->
                anchor.setCol1(colIndex)
                anchor.setRow1(rowIndex)
                anchor.setCol2(colIndex + 1)
                anchor.setRow2(rowIndex + 1)
                anchor.anchorType = ClientAnchor.AnchorType.MOVE_AND_RESIZE
            }
        }
    }

    /**
     * XSSF 전용 앵커 생성 (EMU 단위 오프셋 지원)
     */
    private fun createXssfAnchor(
        rowIndex: Int,
        colIndex: Int,
        mergedRegion: CellRangeAddress?,
        marginPx: Int
    ): XSSFClientAnchor {
        // EMU (English Metric Units) 변환: 1 pixel ≈ 9525 EMUs
        val marginEmu = marginPx * 9525

        return if (mergedRegion != null) {
            XSSFClientAnchor(
                marginEmu, marginEmu, -marginEmu, -marginEmu,
                mergedRegion.firstColumn, mergedRegion.firstRow,
                mergedRegion.lastColumn + 1, mergedRegion.lastRow + 1
            ).apply {
                anchorType = ClientAnchor.AnchorType.MOVE_AND_RESIZE
            }
        } else {
            XSSFClientAnchor(
                marginEmu, marginEmu, -marginEmu, -marginEmu,
                colIndex, rowIndex,
                colIndex + 1, rowIndex + 1
            ).apply {
                anchorType = ClientAnchor.AnchorType.MOVE_AND_RESIZE
            }
        }
    }
}
