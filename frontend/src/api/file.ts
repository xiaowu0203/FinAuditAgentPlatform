import { http } from './request'
import type { AxiosProgressEvent } from 'axios'
import type { FileVO } from '@/types'

/**
 * 上传文件（multipart → file-service）。
 * - 走统一 axios 实例（自动注入 Bearer），网关注入 X-Tenant-Id
 * - 响应拦截器已解包 R.data，返回 FileVO（含 file_record id 与预签名 URL）
 * - onProgress 回调进度（0-100），用于上传进度条
 */
export function uploadFile(file: File, onProgress?: (percent: number) => void): Promise<FileVO> {
  const formData = new FormData()
  formData.append('file', file)
  return http.post<FileVO>('/files/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: (e: AxiosProgressEvent) => {
      if (onProgress && e.total) {
        onProgress(Math.round((e.loaded / e.total) * 100))
      }
    },
  })
}

/** 下载预签名 URL（file-service 返回，响应头带 Content-Disposition: attachment） */
export function getFileDownloadUrl(id: number | string): Promise<string> {
  return http.get<string>(`/files/${id}/download`)
}
