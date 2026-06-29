import axios from 'axios'
import type { InternalAxiosRequestConfig, AxiosResponse, AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

const instance = axios.create({ baseURL: '/api', timeout: 5000 })

instance.interceptors.request.use(
	(config: InternalAxiosRequestConfig) => {
		const token = localStorage.getItem('token')
		if (token) {
			config.headers['token'] = token
		}
		return config
	},
	error => Promise.reject(error),
)

instance.interceptors.response.use(
	(response: AxiosResponse) => {
		const { data, config } = response
		if (data.code === '200') {
			return data.data
		}
		if (data.code === '-1') {
			if (!config.url?.includes('/login')) {
				ElMessage.error(data.msg || '登录状态已过期，请重新登录')
				localStorage.removeItem('token')
				localStorage.removeItem('userInfo')
				window.location.href = '/auth/login'
			}
			return response
		}
		ElMessage.error(data.msg || '请求失败，请稍后再试')
		return Promise.reject(new Error(data.msg || '网络请求失败...'))
	},
	error => Promise.reject(error),
)

const request = {
	post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
		return instance.post(url, data, config) as Promise<T>
	},
	get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T> {
		return instance.get(url, config) as Promise<T>
	},
	put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
		return instance.put(url, data, config) as Promise<T>
	},
	delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T> {
		return instance.delete(url, config) as Promise<T>
	},
}

export default request
