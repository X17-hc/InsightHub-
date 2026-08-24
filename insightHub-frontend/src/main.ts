import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Button from 'ant-design-vue/es/button'
import Checkbox from 'ant-design-vue/es/checkbox'
import Drawer from 'ant-design-vue/es/drawer'
import Form from 'ant-design-vue/es/form'
import Input from 'ant-design-vue/es/input'
import List from 'ant-design-vue/es/list'
import Modal from 'ant-design-vue/es/modal'
import Popconfirm from 'ant-design-vue/es/popconfirm'
import Progress from 'ant-design-vue/es/progress'
import Radio from 'ant-design-vue/es/radio'
import Select from 'ant-design-vue/es/select'
import Spin from 'ant-design-vue/es/spin'
import Switch from 'ant-design-vue/es/switch'
import Table from 'ant-design-vue/es/table'
import Tabs from 'ant-design-vue/es/tabs'
import Tag from 'ant-design-vue/es/tag'
import Tooltip from 'ant-design-vue/es/tooltip'
import 'ant-design-vue/dist/reset.css'
import App from './App.vue'
import router from './router'
import './styles.css'
import './page-styles.css'
import './onboarding.css'

const app = createApp(App)
app.use(createPinia()).use(router)
;[Button, Checkbox, Drawer, Form, Input, List, Modal, Popconfirm, Progress, Radio, Select, Spin, Switch, Table, Tabs, Tag, Tooltip]
  .forEach((component) => app.use(component))
app.mount('#app')
