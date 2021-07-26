
import Vue from 'vue'
import Router from 'vue-router'

Vue.use(Router);


import OrderManager from "./components/OrderManager"

import ReservationManager from "./components/ReservationManager"

import PaymentManager from "./components/PaymentManager"


import Mypage from "./components/Mypage"
export default new Router({
    // mode: 'history',
    base: process.env.BASE_URL,
    routes: [
            {
                path: '/orders',
                name: 'OrderManager',
                component: OrderManager
            },

            {
                path: '/reservations',
                name: 'ReservationManager',
                component: ReservationManager
            },

            {
                path: '/payments',
                name: 'PaymentManager',
                component: PaymentManager
            },


            {
                path: '/mypages',
                name: 'Mypage',
                component: Mypage
            },


    ]
})
