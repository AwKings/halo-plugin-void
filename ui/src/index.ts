import { definePlugin } from '@halo-dev/console-shared'
import ActivityView from './views/ActivityView.vue'
import { IconPlug } from '@halo-dev/components'
import { markRaw } from 'vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/void/activity',
        name: 'VoidActivity',
        component: ActivityView,
        meta: {
          title: '访客互动',
          searchable: true,
          menu: {
            name: '访客互动',
            group: '工具',
            icon: markRaw(IconPlug),
            priority: 10,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})
